package org.myorg.dispatchpay.imports;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.myorg.dispatchpay.audit.AuditChange;
import org.myorg.dispatchpay.audit.AuditEvent;
import org.myorg.dispatchpay.audit.AuditSource;
import org.myorg.dispatchpay.audit.AuditWriter;
import org.myorg.dispatchpay.iam.access.AccessService;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.imports.ImportRepository.JobRow;
import org.myorg.dispatchpay.imports.ImportRepository.StagedRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of the worker steps, each in its own transaction. A step only finishes while
 * its worker still holds the lease, so a worker that lost its lease (it was too slow or crashed and
 * another took over) changes nothing. Work is done with the access the starting member has now: a
 * member who lost the permission or was suspended cannot have the step finished on their behalf.
 */
@Component
class ImportProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImportProcessor.class);

    static final String ROW_ERRORS = "ROW_ERRORS";
    static final String COMMIT_CONFLICT = "COMMIT_CONFLICT";
    static final String ACCESS_REVOKED = "ACCESS_REVOKED";
    static final String PROCESSING_FAILED = "PROCESSING_FAILED";

    private final ImportRepository imports;
    private final Map<ImportType, RowRules> rules = new EnumMap<>(ImportType.class);
    private final AccessService accessService;
    private final ImportProperties properties;
    private final AuditWriter audit;
    private final Clock clock;

    ImportProcessor(ImportRepository imports, List<RowRules> rules, AccessService accessService,
            ImportProperties properties, AuditWriter audit, Clock clock) {
        this.imports = imports;
        rules.forEach(rule -> this.rules.put(rule.type(), rule));
        this.accessService = accessService;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** Leases the next import waiting for a worker under a new lease id. */
    @Transactional
    public Optional<JobRow> claimNext() {
        Instant now = clock.instant();
        return imports.claimNext(UUID.randomUUID(), now, now.plus(properties.lease()));
    }

    /** Gives the claimed step back so the next poll retries it (until it runs out of attempts). */
    @Transactional
    public void release(JobRow job) {
        imports.lockLeased(job.id(), job.leaseOwner(), job.status())
                .ifPresent(locked -> imports.finishLeaseEarly(job.id(), clock.instant()));
    }

    // --- validation -------------------------------------------------------------------------------

    /** The whole file cannot be imported; nothing is staged. */
    @Transactional
    public void rejectFile(JobRow job, String code, String message, Long fileSize, String sha256) {
        if (imports.lockLeased(job.id(), job.leaseOwner(), ImportStatus.VALIDATING).isEmpty()) {
            return;
        }
        if (fileSize != null) {
            imports.recordFile(job.id(), fileSize, sha256, null);
        }
        finish(job, ImportStatus.INVALID, null, null, code, message, job.validatedByMembershipId());
    }

    /**
     * Stages every row with its errors, checked with the current access of the member who started
     * validation. Changes nothing outside the import.
     */
    @Transactional
    public void finishValidation(JobRow job, ImportFile.Parsed file, long fileSize, String sha256) {
        if (imports.lockLeased(job.id(), job.leaseOwner(), ImportStatus.VALIDATING).isEmpty()) {
            return;
        }
        imports.recordFile(job.id(), fileSize, sha256, file.columns());
        UUID initiator = job.validatedByMembershipId();
        Optional<BusinessAccess> access = currentAccess(job, initiator);
        if (access.isEmpty()) {
            imports.replaceRows(job.businessId(), job.id(), List.of());
            finish(job, ImportStatus.INVALID, null, null, ACCESS_REVOKED,
                    "The member who started validation no longer has access to this import", initiator);
            return;
        }
        List<RowRules.Row> rows = file.rows().stream()
                .map(row -> new RowRules.Row(row.rowNumber(), row.values()))
                .toList();
        List<List<RowError>> ruleErrors = rules.get(job.type()).validate(access.get(), rows);
        List<StagedRow> staged = new ArrayList<>();
        int errorRows = 0;
        for (int i = 0; i < rows.size(); i++) {
            ImportFile.ParsedRow parsed = file.rows().get(i);
            List<RowError> errors = new ArrayList<>();
            if (parsed.shapeError() != null) {
                errors.add(parsed.shapeError());
            }
            errors.addAll(ruleErrors.get(i));
            errorRows += errors.isEmpty() ? 0 : 1;
            staged.add(new StagedRow(parsed.rowNumber(), parsed.values(), errors, null));
        }
        imports.replaceRows(job.businessId(), job.id(), staged);
        if (errorRows == 0) {
            finish(job, ImportStatus.VALIDATED, rows.size(), 0, null, null, initiator);
        } else {
            finish(job, ImportStatus.INVALID, rows.size(), errorRows, ROW_ERRORS,
                    errorRows + " of " + rows.size() + " rows have errors; nothing can be imported until every"
                            + " row is valid", initiator);
        }
    }

    // --- commit -----------------------------------------------------------------------------------

    /**
     * Re-checks every staged row with the current data and the committing member's current access,
     * then creates all of them, or none when any row no longer passes (its errors are recorded).
     * One transaction, so a retry after a crash can never create a row twice.
     */
    @Transactional
    public void commit(JobRow job) {
        if (imports.lockLeased(job.id(), job.leaseOwner(), ImportStatus.COMMITTING).isEmpty()) {
            return;
        }
        UUID initiator = job.committedByMembershipId();
        Optional<BusinessAccess> access = currentAccess(job, initiator);
        if (access.isEmpty()) {
            finish(job, ImportStatus.FAILED, job.totalRows(), 0, ACCESS_REVOKED,
                    "The member who committed no longer has access to this import; nothing was imported", initiator);
            return;
        }
        List<StagedRow> staged = imports.allRows(job.id());
        List<RowRules.Row> rows = staged.stream().map(row -> new RowRules.Row(row.rowNumber(), row.values())).toList();
        List<Integer> rowNumbers = rows.stream().map(RowRules.Row::number).toList();
        RowRules rowRules = rules.get(job.type());

        List<List<RowError>> errors = rowRules.validate(access.get(), rows);
        Map<Integer, List<RowError>> errorsByRow = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!errors.get(i).isEmpty()) {
                errorsByRow.put(rows.get(i).number(), errors.get(i));
            }
        }
        if (!errorsByRow.isEmpty()) {
            imports.updateErrors(job.id(), errorsByRow, rowNumbers);
            finish(job, ImportStatus.FAILED, rows.size(), errorsByRow.size(), COMMIT_CONFLICT,
                    errorsByRow.size() + " of " + rows.size() + " rows no longer pass after changes made since"
                            + " validation; nothing was imported", initiator);
            return;
        }
        List<UUID> created = rowRules.commit(access.get(), rows);
        imports.recordResources(job.id(), rowNumbers, created);
        finish(job, ImportStatus.COMMITTED, rows.size(), 0, null, null, initiator);
        log.info("Import {} committed {} {} rows", job.id(), created.size(), job.type());
    }

    /** The step failed on every attempt: it ends without changing anything. */
    @Transactional
    public void giveUp(JobRow job) {
        if (imports.lockLeased(job.id(), job.leaseOwner(), job.status()).isEmpty()) {
            return;
        }
        boolean validating = job.status() == ImportStatus.VALIDATING;
        finish(job, validating ? ImportStatus.INVALID : ImportStatus.FAILED, job.totalRows(), job.errorRows(),
                PROCESSING_FAILED, "The import could not be processed; nothing was imported. Start a new import",
                validating ? job.validatedByMembershipId() : job.committedByMembershipId());
    }

    // --- helpers ----------------------------------------------------------------------------------

    private Optional<BusinessAccess> currentAccess(JobRow job, UUID membershipId) {
        return accessService.resolveMembership(job.businessId(), membershipId).filter(job.type()::allows);
    }

    private void finish(JobRow job, ImportStatus status, Integer totalRows, Integer errorRows, String failureCode,
            String failureMessage, UUID initiator) {
        imports.finish(job.id(), status, totalRows, errorRows, failureCode, failureMessage, clock.instant());
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        changes.put("status", new AuditChange(job.status().name(), status.name()));
        if (totalRows != null) {
            changes.put("totalRows", new AuditChange(null, totalRows));
        }
        if (errorRows != null && errorRows > 0) {
            changes.put("errorRows", new AuditChange(null, errorRows));
        }
        if (failureCode != null) {
            changes.put("failureCode", new AuditChange(null, failureCode));
        }
        String action = job.status() == ImportStatus.VALIDATING ? "import.validation.finish" : "import.commit.finish";
        audit.append(AuditEvent.system(job.businessId(), initiator, AuditSource.IMPORT, action,
                ImportService.RESOURCE, job.id(), changes));
    }
}
