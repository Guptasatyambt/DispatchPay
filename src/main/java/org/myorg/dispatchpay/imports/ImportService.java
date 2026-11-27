package org.myorg.dispatchpay.imports;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.myorg.dispatchpay.audit.AuditChange;
import org.myorg.dispatchpay.audit.AuditEvent;
import org.myorg.dispatchpay.audit.AuditWriter;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.imports.ImportRepository.JobRow;
import org.myorg.dispatchpay.imports.ImportRepository.StagedRow;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.myorg.dispatchpay.platform.storage.ObjectStorage;
import org.myorg.dispatchpay.platform.storage.ObjectStorage.PresignedUrl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV imports of the selected business. Every operation needs {@link Permission#IMPORT_MANAGE} and
 * the permission of the import's {@link ImportType}. The file goes to private object storage
 * through a pre-signed URL; workers parse it into staged rows and, after an explicit commit, create
 * the records in one transaction. Nothing is changed before the commit.
 */
@Service
public class ImportService {

    static final String RESOURCE = "import";
    static final String CONTENT_TYPE = "text/csv";

    private static final int FILE_NAME_MAX = 255;
    private static final Pattern UNSAFE_FILE_NAME = Pattern.compile("[\\p{Cntrl}/\\\\\"]");

    /** The pre-signed upload: send the file with {@code method} to {@code url} with exactly these headers. */
    public record Upload(String url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    public record CreatedImport(
            UUID id,
            ImportType type,
            ImportStatus status,
            String fileName,
            long sizeBytes,
            Instant createdAt,
            Upload upload) {
    }

    public record Failure(String code, String message) {
    }

    /** @param resourceId the record a committed row created */
    public record RowView(int rowNumber, Map<String, String> values, List<RowError> errors, UUID resourceId) {
    }

    /**
     * @param columns         the file's columns in file order, once validated
     * @param commitAvailable whether {@code POST /commit} would be accepted now
     * @param rows            staged rows, only on {@code GET /imports/{id}}
     */
    public record ImportView(
            UUID id,
            ImportType type,
            ImportStatus status,
            String fileName,
            long sizeBytes,
            List<String> columns,
            Integer totalRows,
            Integer errorRows,
            Failure failure,
            boolean commitAvailable,
            UUID createdByMembershipId,
            UUID validatedByMembershipId,
            UUID committedByMembershipId,
            Instant createdAt,
            Instant updatedAt,
            Instant validatedAt,
            Instant committedAt,
            PageResponse<RowView> rows) {
    }

    public record ErrorReport(String url, String fileName, Instant expiresAt) {
    }

    private final ImportRepository imports;
    private final ObjectStorage storage;
    private final ImportProperties properties;
    private final AuditWriter audit;
    private final Clock clock;

    public ImportService(ImportRepository imports, ObjectStorage storage, ImportProperties properties,
            AuditWriter audit, Clock clock) {
        this.imports = imports;
        this.storage = storage;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    // --- M04.S01 create ---------------------------------------------------------------------------

    /**
     * Registers an import of {@code type} and returns where to upload the file: a pre-signed URL
     * that accepts exactly {@code sizeBytes} bytes of {@value #CONTENT_TYPE}. Runs inside the
     * idempotent command's transaction; signing the URL makes no network call.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CreatedImport create(BusinessAccess access, ImportType type, String fileName, long sizeBytes) {
        type.requireAccess(access);
        requireSafeFileName(fileName);
        long maxBytes = properties.maxFileSize().toBytes();
        if (sizeBytes < 1 || sizeBytes > maxBytes) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "sizeBytes must be between 1 and " + maxBytes + " (" + properties.maxFileSize() + ")");
        }
        UUID id = UUID.randomUUID();
        String objectKey = objectPrefix(access.businessId(), id) + "/source.csv";
        PresignedUrl upload = storage.presignUpload(objectKey, CONTENT_TYPE, sizeBytes, properties.uploadUrlTtl());
        Instant now = clock.instant();
        imports.insert(id, access.businessId(), type, fileName, sizeBytes, objectKey, upload.expiresAt(),
                access.membershipId(), now);
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        changes.put("type", new AuditChange(null, type.name()));
        changes.put("fileName", new AuditChange(null, fileName));
        changes.put("sizeBytes", new AuditChange(null, sizeBytes));
        changes.put("status", new AuditChange(null, ImportStatus.AWAITING_UPLOAD.name()));
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "import.create", RESOURCE, id,
                changes));
        return new CreatedImport(id, type, ImportStatus.AWAITING_UPLOAD, fileName, sizeBytes, now,
                new Upload(upload.url(), upload.method(), upload.headers(), upload.expiresAt()));
    }

    // --- M04.S02 validate -------------------------------------------------------------------------

    /**
     * Refuses to start validation of an import whose file was not uploaded yet. Called before the
     * idempotent command because it asks object storage (a network call); later states pass so a
     * retried request can replay.
     */
    public void requireUploaded(BusinessAccess access, UUID id) {
        access.require(Permission.IMPORT_MANAGE);
        JobRow job = imports.find(access.businessId(), id).orElseThrow(ImportService::notFound);
        job.type().requireAccess(access);
        if (job.status() == ImportStatus.AWAITING_UPLOAD && storage.stat(job.objectKey()).isEmpty()) {
            throw new ApiException(ErrorCode.FILE_NOT_UPLOADED,
                    "Upload the file to the import's upload URL before validating it");
        }
    }

    /**
     * Hands the uploaded file to the workers, who parse and check it without changing anything.
     * Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ImportView requestValidation(BusinessAccess access, UUID id) {
        JobRow job = lockForStep(access, id);
        if (job.status() != ImportStatus.AWAITING_UPLOAD) {
            throw new ApiException(ErrorCode.CONFLICT, "This import is already " + describe(job.status()));
        }
        imports.requestStep(id, ImportStatus.VALIDATING, access.membershipId(), clock.instant());
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "import.validate", RESOURCE, id,
                Map.of("status", new AuditChange(job.status().name(), ImportStatus.VALIDATING.name()))));
        return view(reload(access.businessId(), id), null);
    }

    /** The import with a page of its staged rows (only rows with errors when {@code errorsOnly}). */
    @Transactional(readOnly = true)
    public ImportView get(BusinessAccess access, UUID id, boolean errorsOnly, PageQuery page) {
        access.require(Permission.IMPORT_MANAGE);
        JobRow job = imports.find(access.businessId(), id).orElseThrow(ImportService::notFound);
        job.type().requireAccess(access);
        List<RowView> rows = imports.findRows(access.businessId(), id, errorsOnly, page.offset(), page.size())
                .stream()
                .map(row -> new RowView(row.rowNumber(), row.values(), row.errors(), row.resourceId()))
                .toList();
        return view(job, PageResponse.of(rows, page, imports.countRows(access.businessId(), id, errorsOnly)));
    }

    // --- M04.S03 commit ---------------------------------------------------------------------------

    /**
     * Hands a validated import without errors to the workers, who re-check every row and create
     * all of them in one transaction, or none. Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ImportView requestCommit(BusinessAccess access, UUID id) {
        JobRow job = lockForStep(access, id);
        if (!job.type().committable()) {
            throw new ApiException(ErrorCode.IMPORT_COMMIT_UNAVAILABLE,
                    "Imports of this type can be validated but not committed yet");
        }
        if (job.status() != ImportStatus.VALIDATED) {
            throw new ApiException(ErrorCode.CONFLICT, switch (job.status()) {
                case AWAITING_UPLOAD, VALIDATING -> "Validate the import before committing it";
                case INVALID -> "This import has errors; fix the file and start a new import";
                default -> "This import is already " + describe(job.status());
            });
        }
        imports.requestStep(id, ImportStatus.COMMITTING, access.membershipId(), clock.instant());
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "import.commit", RESOURCE, id,
                Map.of("status", new AuditChange(job.status().name(), ImportStatus.COMMITTING.name()))));
        return view(reload(access.businessId(), id), null);
    }

    // --- M04.S04 error report ---------------------------------------------------------------------

    /**
     * A short-lived link to a CSV of the rows with errors: their row number, original values and
     * errors, every cell neutralized against spreadsheet formulas. The report is written to private
     * storage on first request (network calls, so no transaction is held open).
     */
    public ErrorReport errorReport(BusinessAccess access, UUID id) {
        access.require(Permission.IMPORT_MANAGE);
        JobRow job = imports.find(access.businessId(), id).orElseThrow(ImportService::notFound);
        job.type().requireAccess(access);
        if ((job.status() != ImportStatus.INVALID && job.status() != ImportStatus.FAILED)
                || job.errorRows() == null || job.errorRows() == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "This import has no row errors");
        }
        String key = job.errorObjectKey();
        if (key == null) {
            key = objectPrefix(job.businessId(), id) + "/errors-" + job.status().name().toLowerCase(Locale.ROOT)
                    + ".csv";
            storage.write(key, errorCsv(job).getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
            imports.recordErrorObject(id, job.status(), key);
        }
        String fileName = "import-" + id + "-errors.csv";
        PresignedUrl download = storage.presignDownload(key, fileName, properties.downloadUrlTtl());
        return new ErrorReport(download.url(), fileName, download.expiresAt());
    }

    private String errorCsv(JobRow job) {
        List<String> columns = job.columns() == null ? job.type().columns() : job.columns();
        List<List<String>> lines = new ArrayList<>();
        List<String> header = new ArrayList<>();
        header.add("row");
        header.addAll(columns);
        header.add("errors");
        lines.add(header);
        for (StagedRow row : imports.allRows(job.id())) {
            if (row.errors().isEmpty()) {
                continue;
            }
            List<String> line = new ArrayList<>();
            line.add(String.valueOf(row.rowNumber()));
            columns.forEach(column -> line.add(row.values().getOrDefault(column, "")));
            line.add(row.errors().stream()
                    .map(error -> (error.column() == null ? "" : error.column() + ": ") + error.message()
                            + " [" + error.code() + "]")
                    .collect(Collectors.joining("; ")));
            lines.add(line);
        }
        return Csv.write(lines);
    }

    // --- helpers ----------------------------------------------------------------------------------

    static String objectPrefix(UUID businessId, UUID importId) {
        return "imports/" + businessId + "/" + importId;
    }

    static ImportView view(JobRow job, PageResponse<RowView> rows) {
        Failure failure = job.failureCode() == null ? null : new Failure(job.failureCode(), job.failureMessage());
        boolean commitAvailable = job.type().committable() && job.status() == ImportStatus.VALIDATED;
        return new ImportView(job.id(), job.type(), job.status(), job.fileName(), job.declaredSize(), job.columns(),
                job.totalRows(), job.errorRows(), failure, commitAvailable, job.createdByMembershipId(),
                job.validatedByMembershipId(), job.committedByMembershipId(), job.createdAt(), job.updatedAt(),
                job.validatedAt(), job.committedAt(), rows);
    }

    private JobRow lockForStep(BusinessAccess access, UUID id) {
        access.require(Permission.IMPORT_MANAGE);
        JobRow job = imports.lock(access.businessId(), id).orElseThrow(ImportService::notFound);
        job.type().requireAccess(access);
        return job;
    }

    private JobRow reload(UUID businessId, UUID id) {
        return imports.find(businessId, id).orElseThrow(ImportService::notFound);
    }

    private static void requireSafeFileName(String fileName) {
        if (fileName.length() > FILE_NAME_MAX || UNSAFE_FILE_NAME.matcher(fileName).find()
                || !fileName.toLowerCase(Locale.ROOT).endsWith(".csv") || fileName.length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "fileName must be a .csv file name of at most " + FILE_NAME_MAX
                            + " characters without path separators");
        }
    }

    private static String describe(ImportStatus status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Import not found");
    }
}
