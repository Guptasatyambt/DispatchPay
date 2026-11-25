package org.myorg.dispatchpay.godown;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.myorg.dispatchpay.godown.GodownService.GodownView;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.idempotency.IdempotentCommandExecutor;
import org.myorg.dispatchpay.platform.idempotency.IdempotentRequest;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Godowns of the business selected with {@value BusinessAccess#HEADER}. Creating and editing
 * require an {@code Idempotency-Key}; editing also requires the godown's current {@code version}.
 */
@RestController
@RequestMapping("/v1/godowns")
public class GodownController {

    public record CreateGodownRequest(@NotBlank @Size(max = 120) String name) {
    }

    /**
     * @param version the godown's version as last read
     */
    public record UpdateGodownRequest(@NotBlank @Size(max = 120) String name, @NotNull @PositiveOrZero Long version) {
    }

    /** Idempotency payloads: the business (and godown) are part of the content. */
    record CreatePayload(UUID businessId, String name) {
    }

    record UpdatePayload(UUID businessId, UUID godownId, String name, long version) {
    }

    private final GodownService godownService;
    private final IdempotentCommandExecutor idempotentExecutor;

    public GodownController(GodownService godownService, IdempotentCommandExecutor idempotentExecutor) {
        this.godownService = godownService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @GetMapping
    PageResponse<GodownView> list(BusinessAccess access, PageQuery page) {
        return godownService.list(access, page);
    }

    @PostMapping
    ResponseEntity<GodownView> create(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateGodownRequest body,
            BusinessAccess access) {
        String name = body.name().strip();
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "godowns.create",
                idempotencyKey, new CreatePayload(access.businessId(), name));
        return idempotentExecutor
                .execute(request, GodownView.class, () -> godownService.create(access, name))
                .toResponse(HttpStatus.CREATED);
    }

    @PatchMapping("/{id}")
    ResponseEntity<GodownView> update(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody UpdateGodownRequest body,
            BusinessAccess access) {
        UUID godownId = parse(id);
        String name = body.name().strip();
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "godowns.update",
                idempotencyKey, new UpdatePayload(access.businessId(), godownId, name, body.version()));
        return idempotentExecutor
                .execute(request, GodownView.class,
                        () -> godownService.update(access, godownId, name, body.version()))
                .toResponse(HttpStatus.OK);
    }

    /** A malformed id is just another id that does not exist here. */
    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Godown not found");
        }
    }
}
