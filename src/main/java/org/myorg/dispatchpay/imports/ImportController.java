package org.myorg.dispatchpay.imports;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.imports.ImportService.CreatedImport;
import org.myorg.dispatchpay.imports.ImportService.ErrorReport;
import org.myorg.dispatchpay.imports.ImportService.ImportView;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.idempotency.IdempotentCommandExecutor;
import org.myorg.dispatchpay.platform.idempotency.IdempotentRequest;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSV imports of the business selected with {@value BusinessAccess#HEADER}:
 * create (get an upload URL) → upload the file → validate → review rows → commit. Validation and
 * commit run in the background ({@code 202}); poll {@code GET /v1/imports/{id}} for the outcome.
 * Every command requires an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/v1/imports")
public class ImportController {

    /** @param sizeBytes exact size of the file that will be uploaded */
    public record CreateImportRequest(
            @NotBlank String type,
            @NotBlank String fileName,
            @NotNull Long sizeBytes) {
    }

    /** Idempotency payloads: the business (and import) are part of the content. */
    record CreatePayload(UUID businessId, ImportType type, String fileName, long sizeBytes) {
    }

    record StepPayload(UUID businessId, UUID importId) {
    }

    private final ImportService importService;
    private final IdempotentCommandExecutor idempotentExecutor;

    public ImportController(ImportService importService, IdempotentCommandExecutor idempotentExecutor) {
        this.importService = importService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @PostMapping
    ResponseEntity<CreatedImport> create(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateImportRequest body,
            BusinessAccess access) {
        ImportType type = type(body.type());
        String fileName = body.fileName().strip();
        long sizeBytes = body.sizeBytes();
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "imports.create",
                idempotencyKey, new CreatePayload(access.businessId(), type, fileName, sizeBytes));
        return idempotentExecutor
                .execute(request, CreatedImport.class, () -> importService.create(access, type, fileName, sizeBytes))
                .toResponse(HttpStatus.CREATED);
    }

    @PostMapping("/{id}/validate")
    ResponseEntity<ImportView> validate(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            BusinessAccess access) {
        UUID importId = parse(id);
        importService.requireUploaded(access, importId);
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "imports.validate",
                idempotencyKey, new StepPayload(access.businessId(), importId));
        return idempotentExecutor
                .execute(request, ImportView.class, () -> importService.requestValidation(access, importId))
                .toResponse(HttpStatus.ACCEPTED);
    }

    /** The import with a page of its staged rows; {@code errorsOnly} lists only rows with errors. */
    @GetMapping("/{id}")
    ImportView get(BusinessAccess access, PageQuery page, @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean errorsOnly) {
        return importService.get(access, parse(id), errorsOnly, page);
    }

    @PostMapping("/{id}/commit")
    ResponseEntity<ImportView> commit(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            BusinessAccess access) {
        UUID importId = parse(id);
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "imports.commit",
                idempotencyKey, new StepPayload(access.businessId(), importId));
        return idempotentExecutor
                .execute(request, ImportView.class, () -> importService.requestCommit(access, importId))
                .toResponse(HttpStatus.ACCEPTED);
    }

    /** A short-lived download link for the CSV of rows with errors. */
    @GetMapping("/{id}/errors")
    ErrorReport errors(BusinessAccess access, @PathVariable String id) {
        return importService.errorReport(access, parse(id));
    }

    private static ImportType type(String raw) {
        try {
            return ImportType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "type must be one of " + List.of(ImportType.values()));
        }
    }

    /** A malformed id is just another id that does not exist here. */
    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ImportService.notFound();
        }
    }
}
