package org.myorg.dispatchpay.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.idempotency.IdempotentCommandExecutor;
import org.myorg.dispatchpay.platform.idempotency.IdempotentRequest;
import org.myorg.dispatchpay.platform.pagination.CursorPage;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.myorg.dispatchpay.stock.StockService.AdjustmentCommand;
import org.myorg.dispatchpay.stock.StockService.BalanceView;
import org.myorg.dispatchpay.stock.StockService.InwardCommand;
import org.myorg.dispatchpay.stock.StockService.LineInput;
import org.myorg.dispatchpay.stock.StockService.MovementQuery;
import org.myorg.dispatchpay.stock.StockService.MovementView;
import org.myorg.dispatchpay.stock.StockService.StockDocumentView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock of the business selected with {@value BusinessAccess#HEADER}: balances, movement history,
 * opening/inward stock and adjustments. Posting requires an {@code Idempotency-Key}; a retried
 * request returns the original document and posts nothing again.
 */
@RestController
@RequestMapping("/v1/stock")
public class StockController {

    /**
     * @param quantity positive for inward stock; signed for adjustments
     * @param unit     any unit the product counts in; its base unit when omitted
     */
    public record LineRequest(@NotNull UUID productId, @NotNull BigDecimal quantity, @Size(max = 16) String unit) {
    }

    /**
     * @param source    {@code INWARD} (default) or {@code OPENING}: the first stock of a product in the
     *                  godown, refused once the pair has any movement
     * @param reference e.g. the supplier's bill number
     */
    public record InwardRequest(
            @NotNull UUID godownId,
            String source,
            @Size(max = StockService.REFERENCE_MAX) String reference,
            @Size(max = StockService.NOTE_MAX) String note,
            @NotEmpty @Size(max = StockService.MAX_LINES) List<@NotNull @Valid LineRequest> lines) {
    }

    /** @param reason one of {@link AdjustmentReason}; {@code OTHER} needs a note */
    public record AdjustmentRequest(
            @NotNull UUID godownId,
            @NotBlank String reason,
            @Size(max = StockService.NOTE_MAX) String note,
            @NotEmpty @Size(max = StockService.MAX_LINES) List<@NotNull @Valid LineRequest> lines) {
    }

    /** Idempotency payloads: the business is part of the content. */
    record InwardPayload(UUID businessId, InwardCommand command) {
    }

    record AdjustmentPayload(UUID businessId, AdjustmentCommand command) {
    }

    private final StockService stockService;
    private final IdempotentCommandExecutor idempotentExecutor;

    public StockController(StockService stockService, IdempotentCommandExecutor idempotentExecutor) {
        this.stockService = stockService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @GetMapping("/balances")
    PageResponse<BalanceView> balances(
            @RequestParam(required = false) String godownId,
            @RequestParam(required = false) String productId,
            @RequestParam(defaultValue = "false") boolean nonZero,
            BusinessAccess access,
            PageQuery page) {
        return stockService.balances(access, uuid("godownId", godownId), uuid("productId", productId), nonZero, page);
    }

    /**
     * @param from    first posting instant included (ISO-8601)
     * @param to      posting instant excluded (ISO-8601)
     * @param cursor  {@code nextCursor} of the previous page
     */
    @GetMapping("/movements")
    CursorPage<MovementView> movements(
            @RequestParam(required = false) String godownId,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + CursorPage.DEFAULT_SIZE) int size,
            BusinessAccess access) {
        MovementQuery query = new MovementQuery(uuid("godownId", godownId), uuid("productId", productId),
                type == null || type.isBlank() ? null : constant(StockMovementType.class, "type", type),
                instant("from", from), instant("to", to));
        return stockService.movements(access, query, cursor, size);
    }

    @PostMapping("/inwards")
    ResponseEntity<StockDocumentView> inward(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody InwardRequest body,
            BusinessAccess access) {
        StockMovementType source = body.source() == null || body.source().isBlank()
                ? StockMovementType.INWARD
                : constant(StockMovementType.class, "source", body.source());
        InwardCommand command = new InwardCommand(body.godownId(), source, body.reference(), body.note(),
                lines(body.lines()));
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "stock.inward",
                idempotencyKey, new InwardPayload(access.businessId(), command));
        return idempotentExecutor
                .execute(request, StockDocumentView.class, () -> stockService.inward(access, command))
                .toResponse(HttpStatus.CREATED);
    }

    @PostMapping("/adjustments")
    ResponseEntity<StockDocumentView> adjust(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AdjustmentRequest body,
            BusinessAccess access) {
        AdjustmentCommand command = new AdjustmentCommand(body.godownId(),
                constant(AdjustmentReason.class, "reason", body.reason()), body.note(), lines(body.lines()));
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "stock.adjust",
                idempotencyKey, new AdjustmentPayload(access.businessId(), command));
        return idempotentExecutor
                .execute(request, StockDocumentView.class, () -> stockService.adjust(access, command))
                .toResponse(HttpStatus.CREATED);
    }

    private static List<LineInput> lines(List<LineRequest> lines) {
        return lines.stream().map(line -> new LineInput(line.productId(), line.quantity(), line.unit())).toList();
    }

    private static UUID uuid(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, name + " must be a UUID");
        }
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, name + " must be an ISO-8601 instant");
        }
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String name, String value) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    name + " must be one of " + List.of(type.getEnumConstants()));
        }
    }
}
