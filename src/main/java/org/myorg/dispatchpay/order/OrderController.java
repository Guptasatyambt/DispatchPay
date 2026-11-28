package org.myorg.dispatchpay.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.myorg.dispatchpay.dispatch.DispatchService.DispatchView;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.order.OrderService.CancelCommand;
import org.myorg.dispatchpay.order.OrderService.ChargeInput;
import org.myorg.dispatchpay.order.OrderService.DispatchDraftCommand;
import org.myorg.dispatchpay.order.OrderService.LineInput;
import org.myorg.dispatchpay.order.OrderService.NewOrder;
import org.myorg.dispatchpay.order.OrderService.OrderChanges;
import org.myorg.dispatchpay.order.OrderService.OrderQuery;
import org.myorg.dispatchpay.order.OrderService.OrderSummary;
import org.myorg.dispatchpay.order.OrderService.OrderView;
import org.myorg.dispatchpay.order.OrderService.QuantityInput;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.idempotency.IdempotentCommandExecutor;
import org.myorg.dispatchpay.platform.idempotency.IdempotentRequest;
import org.myorg.dispatchpay.platform.pagination.CursorPage;
import org.myorg.dispatchpay.platform.web.PatchRequest;
import org.myorg.dispatchpay.platform.web.PatchRequest.Change;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sales orders of the business selected with {@value BusinessAccess#HEADER}. Every change requires
 * an {@code Idempotency-Key}; edits and confirmation also require the order's current
 * {@code version}. Dates are ISO calendar dates ({@code 2026-11-27}); money is integer paise.
 */
@RestController
@RequestMapping("/v1/orders")
public class OrderController {

    private static final Set<String> PATCH_FIELDS = Set.of("customerId", "godownId", "requestedDate",
            "promisedDate", "note", "lines", "charges", "version");

    /**
     * @param unit            any unit the product counts in; its base unit when omitted
     * @param unitPrice       paise per {@code unit}; the product's price applies at confirmation when omitted
     * @param discountPercent 0 to 100, up to 2 decimals
     * @param taxRate         percent charged on the discounted amount, 0 to 100, up to 2 decimals
     */
    public record LineRequest(@NotNull UUID productId, @NotNull BigDecimal quantity, @Size(max = 16) String unit,
            Long unitPrice, BigDecimal discountPercent, BigDecimal taxRate) {
    }

    /** An untaxed extra charge such as freight; {@code amount} in paise. */
    public record ChargeRequest(String label, Long amount) {
    }

    public record CreateOrderRequest(
            @NotNull UUID customerId,
            @NotNull UUID godownId,
            String requestedDate,
            String promisedDate,
            @Size(max = OrderService.NOTE_MAX) String note,
            @NotEmpty @Size(max = OrderService.MAX_LINES) List<@NotNull @Valid LineRequest> lines,
            @Size(max = OrderService.MAX_CHARGES) List<@NotNull ChargeRequest> charges) {
    }

    public record ConfirmRequest(@NotNull Long version) {
    }

    /** @param quantity in the order line's unit */
    public record QuantityRequest(UUID orderLineId, BigDecimal quantity) {
    }

    /**
     * @param reason one of {@link CancellationReason}; {@code OTHER} needs a note
     * @param lines  quantities to cancel; every remaining quantity when omitted (the only choice for a draft)
     */
    public record CancelRequest(String reason, @Size(max = OrderService.NOTE_MAX) String note,
            @Size(max = OrderService.MAX_LINES) List<@NotNull QuantityRequest> lines) {
    }

    /** @param lines quantities to dispatch; every remaining quantity when omitted */
    public record DispatchDraftRequest(@Size(max = OrderService.NOTE_MAX) String note,
            @Size(max = OrderService.MAX_LINES) List<@NotNull QuantityRequest> lines) {
    }

    /** Idempotency payloads: the business (and order) are part of the content. */
    record CreatePayload(UUID businessId, NewOrder order) {
    }

    record UpdatePayload(UUID businessId, UUID orderId, OrderChanges changes, long version) {
    }

    record ConfirmPayload(UUID businessId, UUID orderId, long version) {
    }

    record CancelPayload(UUID businessId, UUID orderId, CancelCommand command) {
    }

    record DispatchDraftPayload(UUID businessId, UUID orderId, DispatchDraftCommand command) {
    }

    private final OrderService orderService;
    private final IdempotentCommandExecutor idempotentExecutor;
    private final JsonMapper jsonMapper;

    public OrderController(OrderService orderService, IdempotentCommandExecutor idempotentExecutor,
            JsonMapper jsonMapper) {
        this.orderService = orderService;
        this.idempotentExecutor = idempotentExecutor;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @param status        one or more {@link OrderStatus}, comma-separated or repeated
     * @param requestedFrom first requested date included
     * @param requestedTo   last requested date included
     * @param q             order number prefix, ignoring case
     * @param cursor        {@code nextCursor} of the previous page
     */
    @GetMapping
    CursorPage<OrderSummary> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String godownId,
            @RequestParam(required = false) String requestedFrom,
            @RequestParam(required = false) String requestedTo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + CursorPage.DEFAULT_SIZE) int size,
            BusinessAccess access) {
        Set<OrderStatus> statuses = EnumSet.noneOf(OrderStatus.class);
        if (status != null) {
            status.stream().filter(value -> !value.isBlank())
                    .forEach(value -> statuses.add(constant(OrderStatus.class, "status", value)));
        }
        OrderQuery query = new OrderQuery(statuses, uuid("customerId", customerId), uuid("godownId", godownId),
                date("requestedFrom", requestedFrom), date("requestedTo", requestedTo), q);
        return orderService.list(access, query, cursor, size);
    }

    @GetMapping("/{id}")
    OrderView get(BusinessAccess access, @PathVariable String id) {
        return orderService.get(access, parse(id));
    }

    @PostMapping
    ResponseEntity<OrderView> create(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest body,
            BusinessAccess access) {
        if (body.requestedDate() == null || body.requestedDate().isBlank()) {
            throw invalid("requestedDate is required");
        }
        NewOrder order = new NewOrder(body.customerId(), body.godownId(), date("requestedDate", body.requestedDate()),
                date("promisedDate", body.promisedDate()), body.note(), lines(body.lines()), charges(body.charges()));
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "orders.create",
                idempotencyKey, new CreatePayload(access.businessId(), order));
        return idempotentExecutor
                .execute(request, OrderView.class, () -> orderService.create(access, order))
                .toResponse(HttpStatus.CREATED);
    }

    /**
     * Omitted fields are left unchanged; {@code null} clears {@code promisedDate} or {@code note}.
     * {@code lines} and {@code charges} replace the whole list. After confirmation only
     * {@code promisedDate} and {@code note} may be sent. {@code version} is required.
     */
    @PatchMapping("/{id}")
    ResponseEntity<OrderView> update(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @RequestBody JsonNode body,
            BusinessAccess access) {
        UUID orderId = parse(id);
        PatchRequest patch = PatchRequest.of(body, PATCH_FIELDS);
        long version = patch.requiredWholeNumber("version", 0, Long.MAX_VALUE);
        Change<String> promised = patch.optionalText("promisedDate", 10);
        OrderChanges changes = new OrderChanges(
                requiredUuid(body, "customerId"),
                requiredUuid(body, "godownId"),
                body.has("requestedDate") ? date("requestedDate", patch.text("requestedDate", 10)) : null,
                promised == null ? null : new Change<>(date("promisedDate", promised.value())),
                patch.optionalText("note", OrderService.NOTE_MAX),
                body.has("lines") ? lines(List.of(list(body, "lines", LineRequest[].class))) : null,
                body.has("charges") ? charges(List.of(list(body, "charges", ChargeRequest[].class))) : null);
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "orders.update",
                idempotencyKey, new UpdatePayload(access.businessId(), orderId, changes, version));
        return idempotentExecutor
                .execute(request, OrderView.class, () -> orderService.update(access, orderId, changes, version))
                .toResponse(HttpStatus.OK);
    }

    @PostMapping("/{id}/confirm")
    ResponseEntity<OrderView> confirm(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody ConfirmRequest body,
            BusinessAccess access) {
        UUID orderId = parse(id);
        long version = body.version();
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "orders.confirm",
                idempotencyKey, new ConfirmPayload(access.businessId(), orderId, version));
        return idempotentExecutor
                .execute(request, OrderView.class, () -> orderService.confirm(access, orderId, version))
                .toResponse(HttpStatus.OK);
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<OrderView> cancel(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody CancelRequest body,
            BusinessAccess access) {
        UUID orderId = parse(id);
        if (body.reason() == null || body.reason().isBlank()) {
            throw invalid("reason is required");
        }
        CancelCommand command = new CancelCommand(constant(CancellationReason.class, "reason", body.reason()),
                body.note(), quantities(body.lines()));
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "orders.cancel",
                idempotencyKey, new CancelPayload(access.businessId(), orderId, command));
        return idempotentExecutor
                .execute(request, OrderView.class, () -> orderService.cancel(access, orderId, command))
                .toResponse(HttpStatus.OK);
    }

    @PostMapping("/{id}/dispatch-drafts")
    ResponseEntity<DispatchView> createDispatchDraft(
            @RequestHeader(name = IdempotentCommandExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable String id,
            @Valid @RequestBody(required = false) DispatchDraftRequest body,
            BusinessAccess access) {
        UUID orderId = parse(id);
        DispatchDraftCommand command = body == null
                ? new DispatchDraftCommand(null, null)
                : new DispatchDraftCommand(body.note(), quantities(body.lines()));
        IdempotentRequest request = new IdempotentRequest("user:" + access.userId(), "orders.dispatch-drafts.create",
                idempotencyKey, new DispatchDraftPayload(access.businessId(), orderId, command));
        return idempotentExecutor
                .execute(request, DispatchView.class, () -> orderService.createDispatchDraft(access, orderId, command))
                .toResponse(HttpStatus.CREATED);
    }

    // --- parsing ----------------------------------------------------------------------------------

    private static List<LineInput> lines(List<LineRequest> lines) {
        return lines.stream()
                .map(line -> line == null ? null : new LineInput(line.productId(), line.quantity(), line.unit(),
                        line.unitPrice(), line.discountPercent(), line.taxRate()))
                .toList();
    }

    private static List<ChargeInput> charges(List<ChargeRequest> charges) {
        return charges == null ? null : charges.stream()
                .map(charge -> charge == null ? null : new ChargeInput(charge.label(), charge.amount()))
                .toList();
    }

    private static List<QuantityInput> quantities(List<QuantityRequest> lines) {
        return lines == null ? null : lines.stream()
                .map(line -> new QuantityInput(line.orderLineId(), line.quantity()))
                .toList();
    }

    /** A JSON array field of a PATCH body; {@code null} is not allowed. */
    private <T> T[] list(JsonNode body, String field, Class<T[]> type) {
        JsonNode node = body.get(field);
        if (!node.isArray()) {
            throw invalid(field + " must be an array");
        }
        try {
            T[] items = jsonMapper.treeToValue(node, type);
            for (T item : items) {
                if (item == null) {
                    throw invalid(field + " must not contain null");
                }
            }
            return items;
        } catch (JacksonException e) {
            throw invalid(field + " is not valid: " + e.getOriginalMessage());
        }
    }

    /** A PATCH field that cannot be cleared: {@code null} when absent. */
    private static UUID requiredUuid(JsonNode body, String field) {
        if (!body.has(field)) {
            return null;
        }
        JsonNode node = body.get(field);
        if (!node.isString()) {
            throw invalid(field + " must be a UUID");
        }
        return uuid(field, node.asString());
    }

    private static UUID uuid(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw invalid(name + " must be a UUID");
        }
    }

    private static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw invalid(name + " must be a date like 2026-11-27");
        }
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String name, String value) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(name + " must be one of " + List.of(type.getEnumConstants()));
        }
    }

    /** A malformed id is just another id that does not exist here. */
    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw OrderService.notFound();
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
