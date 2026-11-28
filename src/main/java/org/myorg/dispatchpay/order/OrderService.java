package org.myorg.dispatchpay.order;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.myorg.dispatchpay.audit.AuditChange;
import org.myorg.dispatchpay.audit.AuditEvent;
import org.myorg.dispatchpay.audit.AuditWriter;
import org.myorg.dispatchpay.catalog.ProductRules;
import org.myorg.dispatchpay.catalog.ProductService;
import org.myorg.dispatchpay.catalog.ProductService.OrderProduct;
import org.myorg.dispatchpay.catalog.Unit;
import org.myorg.dispatchpay.customer.CustomerService;
import org.myorg.dispatchpay.customer.CustomerService.OrderCustomer;
import org.myorg.dispatchpay.dispatch.DispatchService;
import org.myorg.dispatchpay.dispatch.DispatchService.DispatchView;
import org.myorg.dispatchpay.dispatch.DispatchService.DraftLine;
import org.myorg.dispatchpay.godown.GodownService;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.order.OrderMoney.LineAmounts;
import org.myorg.dispatchpay.order.OrderRepository.CancellationLineRow;
import org.myorg.dispatchpay.order.OrderRepository.CancellationRow;
import org.myorg.dispatchpay.order.OrderRepository.ChargeRow;
import org.myorg.dispatchpay.order.OrderRepository.LineRow;
import org.myorg.dispatchpay.order.OrderRepository.NewLine;
import org.myorg.dispatchpay.order.OrderRepository.OrderFilter;
import org.myorg.dispatchpay.order.OrderRepository.OrderPosition;
import org.myorg.dispatchpay.order.OrderRepository.OrderRow;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.pagination.CursorPage;
import org.myorg.dispatchpay.platform.web.PatchRequest.Change;
import org.myorg.dispatchpay.stock.StockQuantity;
import org.myorg.dispatchpay.stock.StockQuantity.Converted;
import org.myorg.dispatchpay.stock.StockQuantity.Problem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sales orders of the selected business. Reading needs {@link Permission#ORDER_READ}; creating,
 * editing, confirming and cancelling need the matching {@code ORDER_*} permission, and creating a
 * dispatch draft {@link Permission#DISPATCH_CREATE}. Workers only see and act on orders of godowns
 * in their scope. Orders never move stock: only a confirmed dispatch does. Every change is audited
 * in its transaction, and every change to a confirmed order writes its next committed revision.
 */
@Service
public class OrderService {

    static final String RESOURCE = "sales_order";

    public static final int MAX_LINES = 100;
    public static final int MAX_CHARGES = 10;
    public static final int NOTE_MAX = 500;
    public static final int CHARGE_LABEL_MAX = 60;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // --- commands ---------------------------------------------------------------------------------

    /**
     * @param unit            any unit the product counts in; its base unit when {@code null}
     * @param unitPrice       paise per {@code unit}; when {@code null} the product's price applies at confirmation
     * @param discountPercent 0 to 100 with up to 2 decimals; 0 when {@code null}
     * @param taxRate         percent of the discounted amount, 0 to 100 with up to 2 decimals; 0 when {@code null}
     */
    public record LineInput(UUID productId, BigDecimal quantity, String unit, Long unitPrice,
            BigDecimal discountPercent, BigDecimal taxRate) {
    }

    /** @param amount paise */
    public record ChargeInput(String label, Long amount) {
    }

    public record NewOrder(UUID customerId, UUID godownId, LocalDate requestedDate, LocalDate promisedDate,
            String note, List<LineInput> lines, List<ChargeInput> charges) {
    }

    /**
     * {@code null} leaves a field unchanged; a {@link Change} of {@code null} clears it. {@code lines}
     * and {@code charges} replace the draft's whole list.
     */
    public record OrderChanges(UUID customerId, UUID godownId, LocalDate requestedDate, Change<LocalDate> promisedDate,
            Change<String> note, List<LineInput> lines, List<ChargeInput> charges) {

        boolean draftOnly() {
            return customerId != null || godownId != null || requestedDate != null || lines != null
                    || charges != null;
        }
    }

    /** @param quantity in the order line's unit */
    public record QuantityInput(UUID orderLineId, BigDecimal quantity) {
    }

    /** {@code lines} empty or {@code null}: every remaining quantity. */
    public record CancelCommand(CancellationReason reason, String note, List<QuantityInput> lines) {
    }

    /** {@code lines} empty or {@code null}: every remaining quantity. */
    public record DispatchDraftCommand(String note, List<QuantityInput> lines) {
    }

    /** Filters of {@link #list}; {@code null} fields do not filter. Dates are inclusive. */
    public record OrderQuery(Set<OrderStatus> statuses, UUID customerId, UUID godownId, LocalDate requestedFrom,
            LocalDate requestedTo, String number) {
    }

    // --- views ------------------------------------------------------------------------------------

    public record CustomerRef(UUID id, String name) {
    }

    /**
     * Quantities are in {@code baseUnit} except {@code quantity} (in {@code unit}). Amounts are in
     * paise, for the ordered quantity less what was cancelled; {@code null} while a draft line has
     * no price and its product has none either.
     *
     * @param unitPrice          the line's own price; on a confirmed order, the committed price
     * @param effectiveUnitPrice {@code unitPrice}, or on a draft the product's current price per {@code unit}
     * @param remainingQuantity  neither dispatched nor cancelled
     */
    public record OrderLineView(
            UUID id,
            int line,
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantity,
            String unit,
            BigDecimal unitFactor,
            BigDecimal baseQuantity,
            String baseUnit,
            Long unitPrice,
            Long effectiveUnitPrice,
            BigDecimal discountPercent,
            BigDecimal taxRate,
            BigDecimal dispatchedQuantity,
            BigDecimal cancelledQuantity,
            BigDecimal remainingQuantity,
            Long amount,
            Long discount,
            Long tax,
            Long total) {
    }

    public record ChargeView(String label, long amount) {
    }

    /** Paise. {@code total = amount - discount + tax + charges}; charges are dropped once the order is cancelled. */
    public record Totals(long amount, long discount, long tax, long charges, long total) {
    }

    public record CancellationLineView(UUID orderLineId, BigDecimal quantity, String unit, BigDecimal baseQuantity) {
    }

    public record CancellationView(UUID id, CancellationReason reason, String note, UUID cancelledByMembershipId,
            Instant cancelledAt, List<CancellationLineView> lines) {
    }

    public record DispatchRef(UUID id, String status, Instant createdAt) {
    }

    /**
     * @param number   assigned at confirmation
     * @param revision the latest committed revision, 0 for a draft
     * @param totals   {@code null} while a draft line has no price
     */
    public record OrderView(
            UUID id,
            String number,
            OrderStatus status,
            CustomerRef customer,
            UUID godownId,
            String godownName,
            LocalDate requestedDate,
            LocalDate promisedDate,
            String note,
            int revision,
            List<OrderLineView> lines,
            List<ChargeView> charges,
            Totals totals,
            List<CancellationView> cancellations,
            List<DispatchRef> dispatches,
            UUID createdByMembershipId,
            UUID updatedByMembershipId,
            UUID confirmedByMembershipId,
            Instant createdAt,
            Instant updatedAt,
            Instant confirmedAt,
            long version) {
    }

    /** @param total paise; {@code null} while a draft line has no price */
    public record OrderSummary(
            UUID id,
            String number,
            OrderStatus status,
            CustomerRef customer,
            UUID godownId,
            String godownName,
            LocalDate requestedDate,
            LocalDate promisedDate,
            int lineCount,
            Long total,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    private final OrderRepository orders;
    private final CustomerService customers;
    private final ProductService products;
    private final GodownService godowns;
    private final DispatchService dispatches;
    private final AuditWriter audit;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OrderService(OrderRepository orders, CustomerService customers, ProductService products,
            GodownService godowns, DispatchService dispatches, AuditWriter audit, JsonMapper jsonMapper,
            Clock clock) {
        this.orders = orders;
        this.customers = customers;
        this.products = products;
        this.godowns = godowns;
        this.dispatches = dispatches;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    // --- M06.S01 create ---------------------------------------------------------------------------

    /**
     * Creates a draft order. Nothing is reserved, posted or owed: a draft creates neither a stock
     * movement nor a receivable. Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderView create(BusinessAccess access, NewOrder order) {
        access.require(Permission.ORDER_CREATE);
        requireGodown(access, order.godownId());
        requireCustomer(access.businessId(), order.customerId());
        if (order.requestedDate() == null) {
            throw invalid("requestedDate is required");
        }
        String note = text("note", order.note(), NOTE_MAX);
        List<NewLine> lines = lines(access.businessId(), order.lines());
        List<ChargeRow> charges = charges(order.charges());

        UUID id = UUID.randomUUID();
        Instant now = now();
        orders.insert(id, access.businessId(), order.customerId(), order.godownId(), order.requestedDate(),
                order.promisedDate(), note, access.membershipId(), now);
        orders.insertLines(access.businessId(), id, lines);
        orders.insertCharges(access.businessId(), id, charges);
        OrderView created = requireTotalFits(view(access.businessId(), find(access, id)));

        Map<String, AuditChange> changes = new LinkedHashMap<>();
        changes.put("status", new AuditChange(null, OrderStatus.DRAFT.name()));
        changes.put("customerId", new AuditChange(null, order.customerId()));
        changes.put("godownId", new AuditChange(null, order.godownId()));
        changes.put("requestedDate", new AuditChange(null, order.requestedDate()));
        if (order.promisedDate() != null) {
            changes.put("promisedDate", new AuditChange(null, order.promisedDate()));
        }
        if (note != null) {
            changes.put("note", new AuditChange(null, note));
        }
        changes.put("lines", new AuditChange(null, auditLines(created.lines())));
        if (!charges.isEmpty()) {
            changes.put("charges", new AuditChange(null, created.charges()));
        }
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "order.create", RESOURCE, id,
                changes));
        return created;
    }

    // --- M06.S02 read -----------------------------------------------------------------------------

    /** An order of a godown outside the member's scope is not found, like one of another business. */
    @Transactional(readOnly = true)
    public OrderView get(BusinessAccess access, UUID id) {
        access.require(Permission.ORDER_READ);
        return view(access.businessId(), find(access, id));
    }

    /** Orders newest first, a cursor page at a time. Workers see only orders of their godowns. */
    @Transactional(readOnly = true)
    public CursorPage<OrderSummary> list(BusinessAccess access, OrderQuery query, String cursor, int size) {
        access.require(Permission.ORDER_READ);
        if (size < 1 || size > CursorPage.MAX_SIZE) {
            throw invalid("size must be between 1 and " + CursorPage.MAX_SIZE);
        }
        if (query.requestedFrom() != null && query.requestedTo() != null
                && query.requestedFrom().isAfter(query.requestedTo())) {
            throw invalid("requestedFrom must not be after requestedTo");
        }
        if (query.godownId() != null) {
            access.requireGodown(query.godownId());
        }
        OrderPosition after = cursor == null || cursor.isBlank() ? null : decodeCursor(cursor);
        String number = query.number() == null || query.number().isBlank() ? null : query.number().strip();
        List<OrderRow> rows = orders.search(access.businessId(), access.godownScope().all(), access.membershipId(),
                new OrderFilter(query.statuses(), query.customerId(), query.godownId(), query.requestedFrom(),
                        query.requestedTo(), number), after, size + 1);
        boolean more = rows.size() > size;
        List<OrderRow> page = more ? rows.subList(0, size) : rows;
        List<UUID> ids = page.stream().map(OrderRow::id).toList();
        Map<UUID, List<LineRow>> lines = orders.findLines(access.businessId(), ids);
        Map<UUID, List<ChargeRow>> charges = orders.findCharges(access.businessId(), ids);
        List<OrderSummary> items = page.stream().map(row -> {
            List<OrderLineView> lineViews = lineViews(lines.getOrDefault(row.id(), List.of()));
            Totals totals = totals(row.status(), lineViews, charges.getOrDefault(row.id(), List.of()));
            return new OrderSummary(row.id(), row.number(), row.status(),
                    new CustomerRef(row.customerId(), row.customerName()), row.godownId(), row.godownName(),
                    row.requestedDate(), row.promisedDate(), lineViews.size(), totals == null ? null : totals.total(),
                    row.createdAt(), row.updatedAt(), row.version());
        }).toList();
        return new CursorPage<>(items, more ? encodeCursor(page.getLast()) : null);
    }

    // --- M06.S03 edit -----------------------------------------------------------------------------

    /**
     * Edits an order last read at {@code expectedVersion}; a stale version conflicts. A draft may
     * change everything. A confirmed order may change only its promised date and note, so committed
     * lines, prices and fulfilled quantities are never overwritten; each such change is the next
     * committed revision. A closed order cannot change. Runs inside the idempotent command's
     * transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderView update(BusinessAccess access, UUID id, OrderChanges requested, long expectedVersion) {
        access.require(Permission.ORDER_EDIT);
        OrderRow order = lock(access, id);
        if (order.version() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "This order was changed by another request; reload and retry");
        }
        if (order.status() == OrderStatus.DRAFT) {
            return updateDraft(access, order, requested);
        }
        if (!order.status().open()) {
            throw new ApiException(ErrorCode.INVALID_ORDER_STATE, "A " + order.status() + " order cannot be edited");
        }
        if (requested.draftOnly()) {
            throw new ApiException(ErrorCode.INVALID_ORDER_STATE,
                    "Only promisedDate and note can change after an order is confirmed");
        }
        LocalDate promisedDate = apply(requested.promisedDate(), order.promisedDate());
        String note = requested.note() == null ? order.note() : text("note", requested.note().value(), NOTE_MAX);
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        diff(changes, "promisedDate", order.promisedDate(), promisedDate);
        diff(changes, "note", order.note(), note);
        if (changes.isEmpty()) {
            return view(access.businessId(), order);
        }
        Instant now = now();
        int revision = order.revision() + 1;
        orders.updateCommitted(id, promisedDate, note, revision, access.membershipId(), now);
        OrderView updated = commitRevision(access, id, revision, "EDIT", now);
        changes.put("revision", new AuditChange(order.revision(), revision));
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), "order.update", RESOURCE, id,
                changes));
        return updated;
    }

    private OrderView updateDraft(BusinessAccess access, OrderRow order, OrderChanges requested) {
        UUID businessId = access.businessId();
        UUID customerId = requested.customerId() == null ? order.customerId() : requested.customerId();
        UUID godownId = requested.godownId() == null ? order.godownId() : requested.godownId();
        if (!customerId.equals(order.customerId())) {
            requireCustomer(businessId, customerId);
        }
        if (!godownId.equals(order.godownId())) {
            requireGodown(access, godownId);
        }
        LocalDate requestedDate = requested.requestedDate() == null ? order.requestedDate() : requested.requestedDate();
        LocalDate promisedDate = apply(requested.promisedDate(), order.promisedDate());
        String note = requested.note() == null ? order.note() : text("note", requested.note().value(), NOTE_MAX);
        List<NewLine> lines = requested.lines() == null ? null : lines(businessId, requested.lines());
        List<ChargeRow> charges = requested.charges() == null ? null : charges(requested.charges());

        OrderView before = view(businessId, order);
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        diff(changes, "customerId", order.customerId(), customerId);
        diff(changes, "godownId", order.godownId(), godownId);
        diff(changes, "requestedDate", order.requestedDate(), requestedDate);
        diff(changes, "promisedDate", order.promisedDate(), promisedDate);
        diff(changes, "note", order.note(), note);
        if (changes.isEmpty() && lines == null && charges == null) {
            return before;
        }
        orders.updateDraft(order.id(), customerId, godownId, requestedDate, promisedDate, note, access.membershipId(),
                now());
        if (lines != null) {
            orders.deleteLines(order.id());
            orders.insertLines(businessId, order.id(), lines);
        }
        if (charges != null) {
            orders.deleteCharges(order.id());
            orders.insertCharges(businessId, order.id(), charges);
        }
        OrderView after = requireTotalFits(view(businessId, find(access, order.id())));
        if (lines != null) {
            diff(changes, "lines", auditLines(before.lines()), auditLines(after.lines()));
        }
        if (charges != null) {
            diff(changes, "charges", before.charges(), after.charges());
        }
        if (!changes.isEmpty()) {
            audit.append(AuditEvent.manual(businessId, access.membershipId(), "order.update", RESOURCE, order.id(),
                    changes));
        }
        return after;
    }

    // --- M06.S04 confirm --------------------------------------------------------------------------

    /**
     * Confirms a draft last read at {@code expectedVersion}: every product must still be active and
     * count in the line's unit, and every line gets a price (its own, or the product's price per the
     * line's unit). The customer's credit limit, when set, must cover this order plus the remaining
     * value of their other open orders; nobody can override it. Assigns the order number and stores
     * revision 1. Stock is not touched. Confirming an order that is already confirmed returns it
     * unchanged. Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderView confirm(BusinessAccess access, UUID id, long expectedVersion) {
        access.require(Permission.ORDER_CONFIRM);
        UUID businessId = access.businessId();
        OrderRow order = lock(access, id);
        if (order.number() != null && order.status() != OrderStatus.CANCELLED) {
            return view(businessId, order);
        }
        if (order.status() != OrderStatus.DRAFT) {
            throw new ApiException(ErrorCode.INVALID_ORDER_STATE, "A " + order.status() + " order cannot be confirmed");
        }
        if (order.version() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "This order was changed by another request; reload and retry");
        }
        List<LineRow> lines = orders.findLines(businessId, List.of(id)).getOrDefault(id, List.of());
        if (lines.isEmpty()) {
            throw invalid("An order needs at least one line");
        }
        Map<UUID, OrderProduct> found = products.findForOrders(businessId,
                lines.stream().map(LineRow::productId).toList());
        List<Map<String, Object>> prices = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LineRow line = lines.get(i);
            OrderProduct product = found.get(line.productId());
            if (product.stock().status() != ProductService.Status.ACTIVE) {
                throw new ApiException(ErrorCode.PRODUCT_INACTIVE,
                        "lines[" + i + "]: product " + line.sku() + " is inactive and cannot be ordered");
            }
            StockQuantity quantity = switch (StockQuantity.convert(product.stock(), line.quantity(),
                    Unit.valueOf(line.unit()))) {
                case Converted converted -> converted.quantity();
                case Problem problem -> throw lineError(i, problem.message());
            };
            Long unitPrice = line.unitPrice();
            if (unitPrice == null && product.price() == null) {
                throw lineError(i, "product " + line.sku() + " has no price; set the line's unitPrice");
            }
            long price;
            try {
                price = unitPrice != null ? unitPrice : OrderMoney.unitPrice(product.price(), quantity.factor());
                OrderMoney.line(price, quantity.base(), quantity.factor(), line.discountPercent(), line.taxRate());
            } catch (ArithmeticException e) {
                throw lineError(i, "amount is too large");
            }
            orders.commitLine(line.id(), price, quantity.factor(), quantity.base());
            Map<String, Object> committed = new LinkedHashMap<>();
            committed.put("lineId", line.id());
            committed.put("unitPrice", price);
            committed.put("priceSource", unitPrice != null ? "LINE" : "PRODUCT");
            committed.put("baseQuantity", quantity.base());
            prices.add(committed);
        }
        OrderView priced = requireTotalFits(view(businessId, order));
        long total = priced.totals().total();

        OrderCustomer customer = customers.lockForOrder(businessId, order.customerId()).orElseThrow();
        long exposure = 0;
        if (customer.creditLimit() != null) {
            exposure = openOrderValue(businessId, customer.id(), id);
            if (exposure + total > customer.creditLimit()) {
                throw new ApiException(ErrorCode.CREDIT_LIMIT_EXCEEDED, "Confirming this order (" + total
                        + " paise) with " + exposure + " paise of other open orders would exceed the credit limit"
                        + " of " + customer.name() + " (" + customer.creditLimit() + " paise)");
            }
        }

        Instant now = now();
        String number = "SO-%06d".formatted(orders.nextNumber(businessId));
        orders.markConfirmed(id, number, access.membershipId(), now);
        OrderView confirmed = commitRevision(access, id, 1, "CONFIRM", now);
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        changes.put("status", new AuditChange(OrderStatus.DRAFT.name(), OrderStatus.CONFIRMED.name()));
        changes.put("number", new AuditChange(null, number));
        changes.put("revision", new AuditChange(0, 1));
        changes.put("total", new AuditChange(null, total));
        if (customer.creditLimit() != null) {
            changes.put("creditCheck", new AuditChange(null, Map.of("creditLimit", customer.creditLimit(),
                    "otherOpenOrders", exposure)));
        }
        changes.put("lines", new AuditChange(null, prices));
        audit.append(AuditEvent.manual(businessId, access.membershipId(), "order.confirm", RESOURCE, id, changes));
        return confirmed;
    }

    /** What the customer's other open orders are still worth: remaining quantities plus their charges. */
    private long openOrderValue(UUID businessId, UUID customerId, UUID exceptOrderId) {
        List<UUID> open = orders.openOrdersOfCustomer(businessId, customerId, exceptOrderId);
        Map<UUID, List<LineRow>> lines = orders.findLines(businessId, open);
        Map<UUID, List<ChargeRow>> charges = orders.findCharges(businessId, open);
        long value = 0;
        for (UUID orderId : open) {
            for (LineRow line : lines.getOrDefault(orderId, List.of())) {
                if (line.remaining().signum() > 0) {
                    value += OrderMoney.line(line.unitPrice(), line.remaining(), line.unitFactor(),
                            line.discountPercent(), line.taxRate()).total();
                }
            }
            for (ChargeRow charge : charges.getOrDefault(orderId, List.of())) {
                value += charge.amount();
            }
        }
        return value;
    }

    // --- M06.S05 cancel ---------------------------------------------------------------------------

    /**
     * Cancels quantities that are neither dispatched nor cancelled, with a reason. A draft is
     * cancelled whole. A confirmed order cancels the given quantities, or every remaining quantity
     * when no lines are given; dispatched history is kept. Open dispatch drafts are not blocked:
     * confirming them rechecks what remains. Once nothing remains the order closes: CANCELLED when
     * nothing was dispatched, otherwise FULFILLED. Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderView cancel(BusinessAccess access, UUID id, CancelCommand command) {
        access.require(Permission.ORDER_CANCEL);
        if (command.reason() == null) {
            throw invalid("reason is required");
        }
        String note = text("note", command.note(), NOTE_MAX);
        if (command.reason() == CancellationReason.OTHER && note == null) {
            throw invalid("note is required when reason is OTHER");
        }
        UUID businessId = access.businessId();
        OrderRow order = lock(access, id);
        boolean all = command.lines() == null || command.lines().isEmpty();
        if (order.status() == OrderStatus.DRAFT && !all) {
            throw invalid("A draft is cancelled whole; omit lines");
        }
        if (order.status() != OrderStatus.DRAFT && !order.status().open()) {
            throw new ApiException(ErrorCode.INVALID_ORDER_STATE, "A " + order.status() + " order cannot be cancelled");
        }
        List<LineRow> lines = orders.findLines(businessId, List.of(id)).getOrDefault(id, List.of());
        List<Taken> taken = all ? everythingRemaining(lines) : taken(lines, command.lines(), "cancel");
        if (taken.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ORDER_STATE, "Nothing is left to cancel on this order");
        }

        Instant now = now();
        UUID cancellationId = UUID.randomUUID();
        orders.insertCancellation(cancellationId, businessId, id, command.reason(), note, access.membershipId(), now);
        List<Map<String, Object>> auditLines = new ArrayList<>();
        for (Taken take : taken) {
            orders.addCancelled(take.line().id(), take.baseQuantity());
            orders.insertCancellationLine(cancellationId, businessId, take.line().id(), take.quantity(), take.unit(),
                    take.baseQuantity());
            Map<String, Object> auditLine = new LinkedHashMap<>();
            auditLine.put("orderLineId", take.line().id());
            auditLine.put("productId", take.line().productId());
            auditLine.put("quantity", take.quantity());
            auditLine.put("unit", take.unit());
            auditLine.put("baseQuantity", take.baseQuantity());
            auditLine.put("remainingBefore", ProductRules.plain(take.line().remaining()));
            auditLine.put("remainingAfter", ProductRules.plain(take.line().remaining().subtract(take.baseQuantity())));
            auditLines.add(auditLine);
        }
        List<LineRow> after = orders.findLines(businessId, List.of(id)).get(id);
        OrderStatus status = order.status();
        if (after.stream().allMatch(line -> line.remaining().signum() == 0)) {
            status = after.stream().anyMatch(line -> line.dispatchedQuantity().signum() > 0)
                    ? OrderStatus.FULFILLED
                    : OrderStatus.CANCELLED;
        }
        boolean committed = order.status() != OrderStatus.DRAFT;
        int revision = committed ? order.revision() + 1 : 0;
        orders.updateStatus(id, status, revision, access.membershipId(), now);
        OrderView cancelled = committed
                ? commitRevision(access, id, revision, "CANCEL", now)
                : view(businessId, find(access, id));

        Map<String, AuditChange> changes = new LinkedHashMap<>();
        diff(changes, "status", order.status().name(), status.name());
        changes.put("reason", new AuditChange(null, command.reason().name()));
        if (note != null) {
            changes.put("note", new AuditChange(null, note));
        }
        changes.put("cancellationId", new AuditChange(null, cancellationId));
        changes.put("lines", new AuditChange(null, auditLines));
        if (committed) {
            changes.put("revision", new AuditChange(order.revision(), revision));
        }
        audit.append(AuditEvent.manual(businessId, access.membershipId(), "order.cancel", RESOURCE, id, changes));
        return cancelled;
    }

    // --- M06.S06 dispatch drafts ------------------------------------------------------------------

    /**
     * Creates a draft dispatch from a confirmed order, for the given quantities or every remaining
     * quantity, so an order can be fulfilled over several dispatches. Each quantity must be within
     * what is neither dispatched nor cancelled now; other open drafts are not counted, because
     * dispatch confirmation rechecks every linked line under lock, so competing drafts cannot
     * over-fulfil the order. Has no stock effect. Runs inside the idempotent command's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DispatchView createDispatchDraft(BusinessAccess access, UUID id, DispatchDraftCommand command) {
        access.require(Permission.DISPATCH_CREATE);
        String note = text("note", command.note(), NOTE_MAX);
        UUID businessId = access.businessId();
        OrderRow order = lock(access, id);
        if (!order.status().open()) {
            throw new ApiException(ErrorCode.INVALID_ORDER_STATE,
                    "Dispatches can only be drafted from a confirmed order that is not closed; this one is "
                            + order.status());
        }
        List<LineRow> lines = orders.findLines(businessId, List.of(id)).getOrDefault(id, List.of());
        boolean all = command.lines() == null || command.lines().isEmpty();
        List<Taken> taken = all ? everythingRemaining(lines) : taken(lines, command.lines(), "dispatch");
        if (taken.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ORDER_STATE, "Nothing is left to dispatch on this order");
        }
        List<DraftLine> draft = taken.stream()
                .map(take -> new DraftLine(take.line().productId(), take.line().sku(), take.line().baseUnit(),
                        take.line().id(), take.quantity(), take.unit(), take.factor(), take.baseQuantity()))
                .toList();
        return dispatches.createDraft(businessId, access.membershipId(), order.customerId(), order.godownId(), id,
                note, draft, now());
    }

    /** A quantity taken off an order line, as entered and in the base unit. */
    private record Taken(LineRow line, BigDecimal quantity, String unit, BigDecimal factor, BigDecimal baseQuantity) {
    }

    /**
     * The remaining quantity of every line that has some, in the line's unit, or in the base unit
     * when the remainder is not a quantity of the line's unit with at most 6 decimals.
     */
    private static List<Taken> everythingRemaining(List<LineRow> lines) {
        List<Taken> taken = new ArrayList<>();
        for (LineRow line : lines) {
            BigDecimal remaining = ProductRules.plain(line.remaining());
            if (remaining.signum() == 0) {
                continue;
            }
            BigDecimal inUnit = inLineUnit(remaining, line.unitFactor());
            taken.add(inUnit != null
                    ? new Taken(line, inUnit, line.unit(), ProductRules.plain(line.unitFactor()), remaining)
                    : new Taken(line, remaining, line.baseUnit(), BigDecimal.ONE, remaining));
        }
        return taken;
    }

    private static BigDecimal inLineUnit(BigDecimal base, BigDecimal factor) {
        try {
            BigDecimal quantity = ProductRules.plain(base.divide(factor));
            return ProductRules.fitsDecimal(quantity) ? quantity : null;
        } catch (ArithmeticException nonTerminating) {
            return null;
        }
    }

    /** The requested quantities, each in its line's unit and within what remains of that line. */
    private static List<Taken> taken(List<LineRow> lines, List<QuantityInput> inputs, String action) {
        if (inputs.size() > MAX_LINES) {
            throw invalid("lines must have at most " + MAX_LINES + " items");
        }
        Map<UUID, LineRow> byId = new HashMap<>();
        lines.forEach(line -> byId.put(line.id(), line));
        Map<UUID, Integer> firstLine = new HashMap<>();
        List<Taken> taken = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            QuantityInput input = inputs.get(i);
            if (input == null || input.orderLineId() == null || input.quantity() == null) {
                throw lineError(i, "orderLineId and quantity are required");
            }
            Integer first = firstLine.putIfAbsent(input.orderLineId(), i);
            if (first != null) {
                throw lineError(i, "the same order line as lines[" + first + "]; list each line once");
            }
            LineRow line = byId.get(input.orderLineId());
            if (line == null) {
                throw lineError(i, "order line not found");
            }
            if (input.quantity().signum() <= 0) {
                throw lineError(i, "quantity must be greater than zero");
            }
            BigDecimal quantity = ProductRules.plain(input.quantity());
            BigDecimal base = ProductRules.plain(quantity.multiply(line.unitFactor()));
            if (!ProductRules.fitsDecimal(quantity) || !ProductRules.fitsDecimal(base)) {
                throw lineError(i, "quantity must have at most " + ProductRules.MAX_INTEGER_DIGITS + " digits before"
                        + " and " + ProductRules.SCALE + " after the decimal point, also in " + line.baseUnit());
            }
            if (base.compareTo(line.remaining()) > 0) {
                throw new ApiException(ErrorCode.QUANTITY_EXCEEDS_REMAINING, "lines[" + i + "]: cannot " + action + " "
                        + quantity.toPlainString() + " " + line.unit() + " (" + base.toPlainString() + " "
                        + line.baseUnit() + ") of " + line.sku() + "; only "
                        + ProductRules.plain(line.remaining()).toPlainString() + " " + line.baseUnit()
                        + " remain (ordered " + ProductRules.plain(line.baseQuantity()).toPlainString()
                        + ", dispatched " + ProductRules.plain(line.dispatchedQuantity()).toPlainString()
                        + ", cancelled " + ProductRules.plain(line.cancelledQuantity()).toPlainString() + ")");
            }
            taken.add(new Taken(line, quantity, line.unit(), ProductRules.plain(line.unitFactor()), base));
        }
        return taken;
    }

    // --- revisions and views ----------------------------------------------------------------------

    /** Stores the order as it now is as committed revision {@code revision}, and returns it. */
    private OrderView commitRevision(BusinessAccess access, UUID id, int revision, String change, Instant now) {
        OrderView view = view(access.businessId(), find(access, id));
        orders.insertRevision(id, revision, access.businessId(), change, jsonMapper.writeValueAsString(view),
                access.membershipId(), now);
        return view;
    }

    private OrderView view(UUID businessId, OrderRow row) {
        List<OrderLineView> lines = lineViews(orders.findLines(businessId, List.of(row.id()))
                .getOrDefault(row.id(), List.of()));
        List<ChargeRow> charges = orders.findCharges(businessId, List.of(row.id())).getOrDefault(row.id(), List.of());
        Map<UUID, List<CancellationLineView>> cancelledLines = new HashMap<>();
        for (CancellationLineRow line : orders.findCancellationLines(businessId, row.id())) {
            cancelledLines.computeIfAbsent(line.cancellationId(), key -> new ArrayList<>())
                    .add(new CancellationLineView(line.orderLineId(), ProductRules.plain(line.quantity()), line.unit(),
                            ProductRules.plain(line.baseQuantity())));
        }
        List<CancellationView> cancellations = new ArrayList<>();
        for (CancellationRow cancellation : orders.findCancellations(businessId, row.id())) {
            cancellations.add(new CancellationView(cancellation.id(), cancellation.reason(), cancellation.note(),
                    cancellation.cancelledByMembershipId(), cancellation.cancelledAt(),
                    cancelledLines.getOrDefault(cancellation.id(), List.of())));
        }
        List<DispatchRef> dispatchRefs = orders.findDispatches(businessId, row.id()).stream()
                .map(ref -> new DispatchRef(ref.id(), ref.status(), ref.createdAt()))
                .toList();
        return new OrderView(row.id(), row.number(), row.status(), new CustomerRef(row.customerId(), row.customerName()),
                row.godownId(), row.godownName(), row.requestedDate(), row.promisedDate(), row.note(), row.revision(),
                lines, charges.stream().map(charge -> new ChargeView(charge.label(), charge.amount())).toList(),
                totals(row.status(), lines, charges), cancellations, dispatchRefs, row.createdByMembershipId(),
                row.updatedByMembershipId(), row.confirmedByMembershipId(), row.createdAt(), row.updatedAt(),
                row.confirmedAt(), row.version());
    }

    private static List<OrderLineView> lineViews(List<LineRow> rows) {
        List<OrderLineView> views = new ArrayList<>();
        for (LineRow row : rows) {
            Long effective = row.unitPrice() != null ? row.unitPrice()
                    : row.productPrice() != null ? OrderMoney.unitPrice(row.productPrice(), row.unitFactor()) : null;
            LineAmounts amounts = effective == null ? null : OrderMoney.line(effective,
                    row.baseQuantity().subtract(row.cancelledQuantity()), row.unitFactor(), row.discountPercent(),
                    row.taxRate());
            views.add(new OrderLineView(row.id(), row.lineNo(), row.productId(), row.sku(), row.productName(),
                    ProductRules.plain(row.quantity()), row.unit(), ProductRules.plain(row.unitFactor()),
                    ProductRules.plain(row.baseQuantity()), row.baseUnit(), row.unitPrice(), effective,
                    percent(row.discountPercent()), percent(row.taxRate()),
                    ProductRules.plain(row.dispatchedQuantity()), ProductRules.plain(row.cancelledQuantity()),
                    ProductRules.plain(row.remaining()),
                    amounts == null ? null : amounts.amount(), amounts == null ? null : amounts.discount(),
                    amounts == null ? null : amounts.tax(), amounts == null ? null : amounts.total()));
        }
        return views;
    }

    private static Totals totals(OrderStatus status, List<OrderLineView> lines, List<ChargeRow> charges) {
        if (lines.stream().anyMatch(line -> line.total() == null)) {
            return null;
        }
        long amount = lines.stream().mapToLong(OrderLineView::amount).sum();
        long discount = lines.stream().mapToLong(OrderLineView::discount).sum();
        long tax = lines.stream().mapToLong(OrderLineView::tax).sum();
        long extra = status == OrderStatus.CANCELLED ? 0 : charges.stream().mapToLong(ChargeRow::amount).sum();
        return new Totals(amount, discount, tax, extra, amount - discount + tax + extra);
    }

    private static OrderView requireTotalFits(OrderView view) {
        if (view.totals() != null && view.totals().total() > OrderMoney.AMOUNT_MAX) {
            throw invalid("The order total must be at most " + OrderMoney.AMOUNT_MAX + " paise");
        }
        return view;
    }

    private static List<Map<String, Object>> auditLines(List<OrderLineView> lines) {
        List<Map<String, Object>> audited = new ArrayList<>();
        for (OrderLineView line : lines) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("productId", line.productId());
            entry.put("quantity", line.quantity());
            entry.put("unit", line.unit());
            entry.put("baseQuantity", line.baseQuantity());
            entry.put("unitPrice", line.unitPrice());
            entry.put("discountPercent", line.discountPercent());
            entry.put("taxRate", line.taxRate());
            audited.add(entry);
        }
        return audited;
    }

    // --- validation -------------------------------------------------------------------------------

    /**
     * Checks draft lines: 1 to {@value #MAX_LINES}, each active product of this business at most
     * once, a unit it counts in, a positive quantity, and an optional price and percentages.
     */
    private List<NewLine> lines(UUID businessId, List<LineInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_LINES) {
            throw invalid("lines must have 1 to " + MAX_LINES + " items");
        }
        Map<UUID, Integer> firstLine = new HashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            LineInput input = inputs.get(i);
            if (input == null || input.productId() == null || input.quantity() == null) {
                throw lineError(i, "productId and quantity are required");
            }
            Integer first = firstLine.putIfAbsent(input.productId(), i);
            if (first != null) {
                throw lineError(i, "the same product as lines[" + first + "]; list each product once");
            }
        }
        Map<UUID, OrderProduct> found = products.findForOrders(businessId, firstLine.keySet());
        List<NewLine> lines = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            LineInput input = inputs.get(i);
            OrderProduct product = found.get(input.productId());
            if (product == null) {
                throw lineError(i, "product not found");
            }
            if (product.stock().status() != ProductService.Status.ACTIVE) {
                throw new ApiException(ErrorCode.PRODUCT_INACTIVE,
                        "lines[" + i + "]: product " + product.stock().sku() + " is inactive and cannot be ordered");
            }
            if (input.quantity().signum() <= 0) {
                throw lineError(i, "quantity must be greater than zero");
            }
            Unit unit = null;
            if (input.unit() != null && !input.unit().isBlank()) {
                unit = ProductRules.unit(input.unit());
                if (unit == null) {
                    throw lineError(i, "unit must be one of " + List.of(Unit.values()));
                }
            }
            StockQuantity quantity = switch (StockQuantity.convert(product.stock(), input.quantity(), unit)) {
                case Converted converted -> converted.quantity();
                case Problem problem -> throw lineError(i, problem.message());
            };
            Long unitPrice = input.unitPrice();
            if (unitPrice != null && (unitPrice < 0 || unitPrice > ProductRules.PRICE_MAX)) {
                throw lineError(i, "unitPrice must be between 0 and " + ProductRules.PRICE_MAX + " paise");
            }
            BigDecimal discount = percent(i, "discountPercent", input.discountPercent());
            BigDecimal tax = percent(i, "taxRate", input.taxRate());
            Long effective = unitPrice != null ? unitPrice : product.price();
            try {
                if (effective != null) {
                    long price = unitPrice != null ? unitPrice : OrderMoney.unitPrice(effective, quantity.factor());
                    OrderMoney.line(price, quantity.base(), quantity.factor(), discount, tax);
                }
            } catch (ArithmeticException e) {
                throw lineError(i, "amount is too large");
            }
            lines.add(new NewLine(UUID.randomUUID(), i + 1, product.stock().id(), quantity.entered(),
                    quantity.unit().name(), quantity.factor(), quantity.base(), unitPrice, discount, tax));
        }
        return lines;
    }

    private static List<ChargeRow> charges(List<ChargeInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        if (inputs.size() > MAX_CHARGES) {
            throw invalid("charges must have at most " + MAX_CHARGES + " items");
        }
        List<ChargeRow> charges = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            ChargeInput input = inputs.get(i);
            String label = input == null ? null : text("charges[" + i + "].label", input.label(), CHARGE_LABEL_MAX);
            if (label == null) {
                throw invalid("charges[" + i + "]: label is required");
            }
            if (input.amount() == null || input.amount() <= 0 || input.amount() > OrderMoney.AMOUNT_MAX) {
                throw invalid("charges[" + i + "]: amount must be between 1 and " + OrderMoney.AMOUNT_MAX + " paise");
            }
            charges.add(new ChargeRow(null, i + 1, label, input.amount()));
        }
        return charges;
    }

    /** 0 to 100 with at most 2 decimals; 0 when {@code null}. */
    private static BigDecimal percent(int index, String field, BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0 || value.stripTrailingZeros().scale() > 2) {
            throw lineError(index, field + " must be between 0 and 100 with at most 2 decimals");
        }
        return value.setScale(2);
    }

    private static BigDecimal percent(BigDecimal value) {
        return ProductRules.plain(value);
    }

    /** A godown of another business is not found; one of this business outside the scope is denied. */
    private void requireGodown(BusinessAccess access, UUID godownId) {
        if (godownId == null) {
            throw invalid("godownId is required");
        }
        if (godowns.findName(access.businessId(), godownId).isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Godown not found");
        }
        access.requireGodown(godownId);
    }

    private void requireCustomer(UUID businessId, UUID customerId) {
        if (customerId == null) {
            throw invalid("customerId is required");
        }
        if (customers.findForOrder(businessId, customerId).isEmpty()) {
            throw invalid("customerId: customer not found");
        }
    }

    /** The order, if it exists in this business and its godown is in the member's scope. */
    private OrderRow find(BusinessAccess access, UUID id) {
        return orders.find(access.businessId(), id)
                .filter(row -> access.godownScope().includes(row.godownId()))
                .orElseThrow(OrderService::notFound);
    }

    /** {@link #find}, locked until the transaction ends. */
    private OrderRow lock(BusinessAccess access, UUID id) {
        return orders.lock(access.businessId(), id)
                .filter(row -> access.godownScope().includes(row.godownId()))
                .orElseThrow(OrderService::notFound);
    }

    /** Database precision, so a stored order reads back exactly as returned. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static <T> T apply(Change<T> change, T current) {
        return change == null ? current : change.value();
    }

    private static void diff(Map<String, AuditChange> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(field, new AuditChange(before, after));
        }
    }

    /** Stripped text, {@code null} when blank. */
    private static String text(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw invalid(field + " must be at most " + max + " characters");
        }
        return stripped;
    }

    static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Order not found");
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static ApiException lineError(int index, String message) {
        return invalid("lines[" + index + "]: " + message);
    }

    // --- cursor -----------------------------------------------------------------------------------

    static String encodeCursor(OrderRow last) {
        String raw = last.createdAt() + "|" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static OrderPosition decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            return new OrderPosition(Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException | DateTimeParseException e) {
            throw invalid("cursor is invalid");
        }
    }
}
