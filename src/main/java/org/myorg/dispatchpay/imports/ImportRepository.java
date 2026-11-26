package org.myorg.dispatchpay.imports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Imports of one business and their staged rows. Every API query is filtered by the business. */
@Repository
class ImportRepository {

    record JobRow(
            UUID id,
            UUID businessId,
            ImportType type,
            ImportStatus status,
            String fileName,
            long declaredSize,
            String objectKey,
            Instant uploadExpiresAt,
            Long fileSize,
            List<String> columns,
            Integer totalRows,
            Integer errorRows,
            String failureCode,
            String failureMessage,
            String errorObjectKey,
            UUID createdByMembershipId,
            UUID validatedByMembershipId,
            UUID committedByMembershipId,
            UUID leaseOwner,
            int attempts,
            Instant createdAt,
            Instant updatedAt,
            Instant validatedAt,
            Instant committedAt) {
    }

    record StagedRow(int rowNumber, Map<String, String> values, List<RowError> errors, UUID resourceId) {
    }

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> VALUES = new TypeReference<>() {
    };
    private static final TypeReference<List<RowError>> ERRORS = new TypeReference<>() {
    };

    private static final String SELECT = """
            SELECT id, business_id, type, status, file_name, declared_size, object_key, upload_expires_at,
                   file_size, columns::text AS columns, total_rows, error_rows, failure_code, failure_message,
                   error_object_key, created_by_membership_id, validated_by_membership_id,
                   committed_by_membership_id, lease_owner, attempts, created_at, updated_at, validated_at,
                   committed_at
            FROM import_job
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;
    private final JsonMapper json;

    ImportRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch, JsonMapper json) {
        this.jdbc = jdbc;
        this.batch = batch;
        this.json = json;
    }

    // --- jobs -------------------------------------------------------------------------------------

    void insert(UUID id, UUID businessId, ImportType type, String fileName, long declaredSize, String objectKey,
            Instant uploadExpiresAt, UUID membershipId, Instant now) {
        jdbc.sql("""
                        INSERT INTO import_job (id, business_id, type, status, file_name, declared_size, object_key,
                                                upload_expires_at, created_by_membership_id, created_at, updated_at)
                        VALUES (:id, :businessId, :type, 'AWAITING_UPLOAD', :fileName, :declaredSize, :objectKey,
                                :uploadExpiresAt, :membershipId, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("type", type.name())
                .param("fileName", fileName)
                .param("declaredSize", declaredSize)
                .param("objectKey", objectKey)
                .param("uploadExpiresAt", Timestamp.from(uploadExpiresAt))
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .update();
    }

    Optional<JobRow> find(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE business_id = :businessId AND id = :id")
                .param("businessId", businessId)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    Optional<JobRow> lock(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE business_id = :businessId AND id = :id FOR UPDATE")
                .param("businessId", businessId)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** Hands the import to the workers for {@code status}, started by {@code membershipId}. */
    void requestStep(UUID id, ImportStatus status, UUID membershipId, Instant now) {
        String actor = status == ImportStatus.VALIDATING ? "validated_by_membership_id" : "committed_by_membership_id";
        jdbc.sql("UPDATE import_job SET status = :status, " + actor + " = :membershipId, attempts = 0,"
                        + " lease_owner = NULL, lease_expires_at = NULL, updated_at = :now WHERE id = :id")
                .param("status", status.name())
                .param("membershipId", membershipId)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    /**
     * Leases the import that has waited longest for a worker, skipping imports other workers are
     * claiming right now; one whose lease expired (its worker crashed) is claimed again.
     */
    Optional<JobRow> claimNext(UUID leaseOwner, Instant now, Instant leaseExpiresAt) {
        return jdbc.sql("""
                        UPDATE import_job
                        SET lease_owner = :leaseOwner, lease_expires_at = :leaseExpiresAt, attempts = attempts + 1
                        WHERE id = (SELECT id FROM import_job
                                    WHERE status IN ('VALIDATING', 'COMMITTING')
                                      AND (lease_expires_at IS NULL OR lease_expires_at < :now)
                                    ORDER BY updated_at, id
                                    LIMIT 1
                                    FOR UPDATE SKIP LOCKED)
                        RETURNING id, business_id, type, status, file_name, declared_size, object_key,
                                  upload_expires_at, file_size, columns::text AS columns, total_rows, error_rows,
                                  failure_code, failure_message, error_object_key, created_by_membership_id,
                                  validated_by_membership_id, committed_by_membership_id, lease_owner, attempts,
                                  created_at, updated_at, validated_at, committed_at
                        """)
                .param("leaseOwner", leaseOwner)
                .param("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
                .param("now", Timestamp.from(now))
                .query(this::map)
                .optional();
    }

    /** Locks the import while {@code leaseOwner} still holds it in {@code status}; empty once it lost the lease. */
    Optional<JobRow> lockLeased(UUID id, UUID leaseOwner, ImportStatus status) {
        return jdbc.sql(SELECT + " WHERE id = :id AND lease_owner = :leaseOwner AND status = :status FOR UPDATE")
                .param("id", id)
                .param("leaseOwner", leaseOwner)
                .param("status", status.name())
                .query(this::map)
                .optional();
    }

    /** Ends the current lease now, so the next poll can claim the step again. */
    void finishLeaseEarly(UUID id, Instant now) {
        jdbc.sql("UPDATE import_job SET lease_expires_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    /** Records the file the worker read. */
    void recordFile(UUID id, long fileSize, String sha256, List<String> columns) {
        jdbc.sql("""
                        UPDATE import_job SET file_size = :fileSize, file_sha256 = :sha256,
                                              columns = CAST(:columns AS jsonb)
                        WHERE id = :id
                        """)
                .param("fileSize", fileSize)
                .param("sha256", sha256)
                .param("columns", columns == null ? null : json.writeValueAsString(columns))
                .param("id", id)
                .update();
    }

    /** Ends a worker step: the new status, its outcome, and the lease released. */
    void finish(UUID id, ImportStatus status, Integer totalRows, Integer errorRows, String failureCode,
            String failureMessage, Instant now) {
        String stepTime = switch (status) {
            case VALIDATED, INVALID -> ", validated_at = :now";
            case COMMITTED, FAILED -> ", committed_at = :now";
            default -> "";
        };
        jdbc.sql("UPDATE import_job SET status = :status, total_rows = :totalRows, error_rows = :errorRows,"
                        + " failure_code = :failureCode, failure_message = :failureMessage, error_object_key = NULL,"
                        + " lease_owner = NULL, lease_expires_at = NULL, updated_at = :now" + stepTime
                        + " WHERE id = :id")
                .param("status", status.name())
                .param("totalRows", totalRows, Types.INTEGER)
                .param("errorRows", errorRows, Types.INTEGER)
                .param("failureCode", failureCode)
                .param("failureMessage", failureMessage)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    /** Records where the error report was stored, unless another request stored it first. */
    void recordErrorObject(UUID id, ImportStatus status, String key) {
        jdbc.sql("""
                        UPDATE import_job SET error_object_key = :key
                        WHERE id = :id AND status = :status AND error_object_key IS NULL
                        """)
                .param("key", key)
                .param("id", id)
                .param("status", status.name())
                .update();
    }

    // --- staged rows ------------------------------------------------------------------------------

    void replaceRows(UUID businessId, UUID importId, List<StagedRow> rows) {
        jdbc.sql("DELETE FROM import_row WHERE import_id = :importId")
                .param("importId", importId)
                .update();
        SqlParameterSource[] params = rows.stream()
                .map(row -> new MapSqlParameterSource()
                        .addValue("businessId", businessId)
                        .addValue("importId", importId)
                        .addValue("rowNumber", row.rowNumber())
                        .addValue("data", json.writeValueAsString(row.values()))
                        .addValue("errors", json.writeValueAsString(row.errors())))
                .toArray(SqlParameterSource[]::new);
        batch.batchUpdate("""
                INSERT INTO import_row (business_id, import_id, row_number, data, errors)
                VALUES (:businessId, :importId, :rowNumber, CAST(:data AS jsonb), CAST(:errors AS jsonb))
                """, params);
    }

    /** Replaces the errors of every row; rows not listed become valid. */
    void updateErrors(UUID importId, Map<Integer, List<RowError>> errorsByRow, List<Integer> rowNumbers) {
        SqlParameterSource[] params = rowNumbers.stream()
                .map(rowNumber -> new MapSqlParameterSource()
                        .addValue("importId", importId)
                        .addValue("rowNumber", rowNumber)
                        .addValue("errors", json.writeValueAsString(errorsByRow.getOrDefault(rowNumber, List.of()))))
                .toArray(SqlParameterSource[]::new);
        batch.batchUpdate("""
                UPDATE import_row SET errors = CAST(:errors AS jsonb)
                WHERE import_id = :importId AND row_number = :rowNumber
                """, params);
    }

    void recordResources(UUID importId, List<Integer> rowNumbers, List<UUID> resourceIds) {
        SqlParameterSource[] params = new SqlParameterSource[rowNumbers.size()];
        for (int i = 0; i < params.length; i++) {
            params[i] = new MapSqlParameterSource()
                    .addValue("importId", importId)
                    .addValue("rowNumber", rowNumbers.get(i))
                    .addValue("resourceId", resourceIds.get(i));
        }
        batch.batchUpdate("""
                UPDATE import_row SET resource_id = :resourceId
                WHERE import_id = :importId AND row_number = :rowNumber AND resource_id IS NULL
                """, params);
    }

    /** Every staged row of the import, in file order. */
    List<StagedRow> allRows(UUID importId) {
        return jdbc.sql("""
                        SELECT row_number, data::text AS data, errors::text AS errors, resource_id
                        FROM import_row WHERE import_id = :importId ORDER BY row_number
                        """)
                .param("importId", importId)
                .query(this::mapRow)
                .list();
    }

    List<StagedRow> findRows(UUID businessId, UUID importId, boolean errorsOnly, long offset, int limit) {
        return jdbc.sql("""
                        SELECT row_number, data::text AS data, errors::text AS errors, resource_id
                        FROM import_row
                        WHERE business_id = :businessId AND import_id = :importId
                          AND (NOT :errorsOnly OR errors <> '[]'::jsonb)
                        ORDER BY row_number
                        LIMIT :limit OFFSET :offset
                        """)
                .param("businessId", businessId)
                .param("importId", importId)
                .param("errorsOnly", errorsOnly)
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapRow)
                .list();
    }

    long countRows(UUID businessId, UUID importId, boolean errorsOnly) {
        return jdbc.sql("""
                        SELECT count(*) FROM import_row
                        WHERE business_id = :businessId AND import_id = :importId
                          AND (NOT :errorsOnly OR errors <> '[]'::jsonb)
                        """)
                .param("businessId", businessId)
                .param("importId", importId)
                .param("errorsOnly", errorsOnly)
                .query(Long.class)
                .single();
    }

    private StagedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new StagedRow(rs.getInt("row_number"), json.readValue(rs.getString("data"), VALUES),
                json.readValue(rs.getString("errors"), ERRORS), rs.getObject("resource_id", UUID.class));
    }

    private JobRow map(ResultSet rs, int rowNum) throws SQLException {
        String columns = rs.getString("columns");
        return new JobRow(
                rs.getObject("id", UUID.class),
                rs.getObject("business_id", UUID.class),
                ImportType.valueOf(rs.getString("type")),
                ImportStatus.valueOf(rs.getString("status")),
                rs.getString("file_name"),
                rs.getLong("declared_size"),
                rs.getString("object_key"),
                rs.getTimestamp("upload_expires_at").toInstant(),
                rs.getObject("file_size", Long.class),
                columns == null ? null : json.readValue(columns, STRINGS),
                rs.getObject("total_rows", Integer.class),
                rs.getObject("error_rows", Integer.class),
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                rs.getString("error_object_key"),
                rs.getObject("created_by_membership_id", UUID.class),
                rs.getObject("validated_by_membership_id", UUID.class),
                rs.getObject("committed_by_membership_id", UUID.class),
                rs.getObject("lease_owner", UUID.class),
                rs.getInt("attempts"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "validated_at"),
                instant(rs, "committed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
