package org.myorg.dispatchpay.godown;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Godowns of one business. Every query is filtered by the business. */
@Repository
class GodownRepository {

    record GodownRow(UUID id, String name, Instant createdAt, Instant updatedAt, long version) {
    }

    private static final String SELECT = "SELECT id, name, created_at, updated_at, version FROM godown";

    /**
     * Restricts to the caller's godown scope: every godown when {@code allGodowns}, otherwise only
     * those assigned to {@code membershipId}.
     */
    private static final String SCOPED_WHERE = """
             WHERE business_id = :businessId
               AND (:allGodowns OR id IN (SELECT godown_id FROM membership_godown
                                          WHERE business_id = :businessId AND membership_id = :membershipId))
            """;

    private final JdbcClient jdbc;

    GodownRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<GodownRow> findPage(UUID businessId, boolean allGodowns, UUID membershipId, long offset, int limit) {
        return jdbc.sql(SELECT + SCOPED_WHERE + " ORDER BY lower(name), id LIMIT :limit OFFSET :offset")
                .param("businessId", businessId)
                .param("allGodowns", allGodowns)
                .param("membershipId", membershipId)
                .param("limit", limit)
                .param("offset", offset)
                .query(GodownRepository::map)
                .list();
    }

    long count(UUID businessId, boolean allGodowns, UUID membershipId) {
        return jdbc.sql("SELECT count(*) FROM godown" + SCOPED_WHERE)
                .param("businessId", businessId)
                .param("allGodowns", allGodowns)
                .param("membershipId", membershipId)
                .query(Long.class)
                .single();
    }

    boolean nameTaken(UUID businessId, String name, UUID exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM godown
                                       WHERE business_id = :businessId AND lower(name) = lower(:name)
                                         AND id <> :exceptId)
                        """)
                .param("businessId", businessId)
                .param("name", name)
                .param("exceptId", exceptId)
                .query(Boolean.class)
                .single();
    }

    Map<String, UUID> idsByName(UUID businessId, Collection<String> lowerNames) {
        if (lowerNames.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT lower(name) AS name, id FROM godown
                        WHERE business_id = :businessId AND lower(name) IN (:names)
                        """)
                .param("businessId", businessId)
                .param("names", lowerNames)
                .query((rs, rowNum) -> Map.entry(rs.getString("name"), rs.getObject("id", UUID.class)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    void insert(UUID id, UUID businessId, String name, Instant now) {
        jdbc.sql("""
                        INSERT INTO godown (id, business_id, name, created_at, updated_at)
                        VALUES (:id, :businessId, :name, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("name", name)
                .param("now", Timestamp.from(now))
                .update();
    }

    Optional<GodownRow> lock(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE business_id = :businessId AND id = :id FOR UPDATE")
                .param("businessId", businessId)
                .param("id", id)
                .query(GodownRepository::map)
                .optional();
    }

    Optional<GodownRow> find(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE business_id = :businessId AND id = :id")
                .param("businessId", businessId)
                .param("id", id)
                .query(GodownRepository::map)
                .optional();
    }

    void rename(UUID id, String name, Instant now) {
        jdbc.sql("UPDATE godown SET name = :name, version = version + 1, updated_at = :now WHERE id = :id")
                .param("name", name)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    private static GodownRow map(ResultSet rs, int rowNum) throws SQLException {
        return new GodownRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
