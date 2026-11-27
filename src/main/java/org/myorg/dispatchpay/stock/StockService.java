package org.myorg.dispatchpay.stock;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.myorg.dispatchpay.audit.AuditChange;
import org.myorg.dispatchpay.audit.AuditEvent;
import org.myorg.dispatchpay.audit.AuditSource;
import org.myorg.dispatchpay.audit.AuditWriter;
import org.myorg.dispatchpay.catalog.ProductRules;
import org.myorg.dispatchpay.catalog.ProductService;
import org.myorg.dispatchpay.catalog.ProductService.StockProduct;
import org.myorg.dispatchpay.catalog.Unit;
import org.myorg.dispatchpay.godown.GodownService;
import org.myorg.dispatchpay.iam.access.BusinessAccess;
import org.myorg.dispatchpay.iam.access.Permission;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.platform.pagination.CursorPage;
import org.myorg.dispatchpay.platform.pagination.PageQuery;
import org.myorg.dispatchpay.platform.pagination.PageResponse;
import org.myorg.dispatchpay.stock.StockLedger.Posted;
import org.myorg.dispatchpay.stock.StockLedger.Posting;
import org.myorg.dispatchpay.stock.StockQuantity.Converted;
import org.myorg.dispatchpay.stock.StockQuantity.Problem;
import org.myorg.dispatchpay.stock.StockRepository.MovementFilter;
import org.myorg.dispatchpay.stock.StockRepository.MovementPosition;
import org.myorg.dispatchpay.stock.StockRepository.MovementRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock of the selected business. Reading needs {@link Permission#STOCK_READ}, posting opening or
 * inward stock {@link Permission#STOCK_INWARD}, adjusting {@link Permission#STOCK_ADJUST}; every
 * operation is limited to the member's godown scope. Each posting request is one stock document of
 * one godown whose lines all post, or none do; it is audited in the same transaction.
 */
@Service
public class StockService {

    static final String DOCUMENT = "stock_document";
    static final String MOVEMENT = "stock_movement";

    public static final int MAX_LINES = 100;
    public static final int REFERENCE_MAX = 100;
    public static final int NOTE_MAX = 500;

    /** @param unit any unit the product counts in; the base unit when {@code null} */
    public record LineInput(UUID productId, BigDecimal quantity, String unit) {
    }

    /** @param type {@link StockMovementType#OPENING} or {@link StockMovementType#INWARD} */
    public record InwardCommand(UUID godownId, StockMovementType type, String reference, String note,
            List<LineInput> lines) {
    }

    /** Line quantities are signed: positive adds stock, negative removes it. */
    public record AdjustmentCommand(UUID godownId, AdjustmentReason reason, String note, List<LineInput> lines) {
    }

    /**
     * @param quantity     as entered, in {@code unit}
     * @param unitFactor   base units in one {@code unit}
     * @param baseQuantity the signed change in the product's base unit
     */
    public record DocumentLineView(
            int line,
            UUID movementId,
            UUID productId,
            String sku,
            BigDecimal quantity,
            String unit,
            BigDecimal unitFactor,
            BigDecimal baseQuantity,
            String baseUnit,
            BigDecimal balanceAfter) {
    }

    public record StockDocumentView(
            UUID id,
            StockMovementType type,
            UUID godownId,
            String reference,
            AdjustmentReason reason,
            String note,
            UUID createdByMembershipId,
            Instant postedAt,
            List<DocumentLineView> lines) {
    }

    /** @param quantity in the product's base unit */
    public record BalanceView(
            UUID godownId,
            String godownName,
            UUID productId,
            String sku,
            String productName,
            String baseUnit,
            String productStatus,
            BigDecimal quantity,
            Instant updatedAt) {
    }

    /** What posted a movement: a stock document line, or an import row. */
    public record MovementSource(String type, UUID id, int line) {
    }

    /** {@code USER} with the member, or {@code SYSTEM} for background work (see the initiator). */
    public record MovementActor(String type, UUID membershipId) {
    }

    /**
     * @param quantity         signed change in {@code baseUnit}
     * @param enteredQuantity  as entered, in {@code enteredUnit}
     * @param balanceAfter     the godown's stock of the product right after this movement
     * @param reference        inward documents only
     * @param reason           adjustments only
     */
    public record MovementView(
            UUID id,
            StockMovementType type,
            UUID godownId,
            UUID productId,
            String sku,
            BigDecimal quantity,
            String baseUnit,
            BigDecimal enteredQuantity,
            String enteredUnit,
            BigDecimal unitFactor,
            BigDecimal balanceAfter,
            MovementSource source,
            MovementActor actor,
            UUID initiatorMembershipId,
            String reference,
            String reason,
            String note,
            Instant postedAt) {
    }

    /** Filters of {@link #movements}; {@code null} fields do not filter. {@code to} is exclusive. */
    public record MovementQuery(UUID godownId, UUID productId, StockMovementType type, Instant from, Instant to) {
    }

    /** One row of an opening stock import, already validated. */
    public record ImportedOpening(int rowNumber, UUID godownId, StockProduct product, StockQuantity quantity) {
    }

    private final StockRepository stock;
    private final StockLedger ledger;
    private final ProductService products;
    private final GodownService godowns;
    private final AuditWriter audit;
    private final Clock clock;

    public StockService(StockRepository stock, StockLedger ledger, ProductService products, GodownService godowns,
            AuditWriter audit, Clock clock) {
        this.stock = stock;
        this.ledger = ledger;
        this.products = products;
        this.godowns = godowns;
        this.audit = audit;
        this.clock = clock;
    }

    // --- M05.S01 balances -------------------------------------------------------------------------

    /**
     * Balances of the product and godown pairs that have had a movement, zero balances included
     * unless {@code nonZero}, ordered by godown then SKU. Workers see only their godowns.
     */
    @Transactional(readOnly = true)
    public PageResponse<BalanceView> balances(BusinessAccess access, UUID godownId, UUID productId, boolean nonZero,
            PageQuery page) {
        access.require(Permission.STOCK_READ);
        if (godownId != null) {
            access.requireGodown(godownId);
        }
        boolean all = access.godownScope().all();
        List<BalanceView> items = stock.findBalances(access.businessId(), all, access.membershipId(), godownId,
                        productId, nonZero, page.offset(), page.size()).stream()
                .map(row -> new BalanceView(row.godownId(), row.godownName(), row.productId(), row.sku(),
                        row.productName(), row.baseUnit(), row.productStatus(), ProductRules.plain(row.quantity()),
                        row.updatedAt()))
                .toList();
        return PageResponse.of(items, page, stock.countBalances(access.businessId(), all, access.membershipId(),
                godownId, productId, nonZero));
    }

    // --- M05.S02 movements ------------------------------------------------------------------------

    /** Movement history, newest first, a cursor page at a time. Workers see only their godowns. */
    @Transactional(readOnly = true)
    public CursorPage<MovementView> movements(BusinessAccess access, MovementQuery query, String cursor, int size) {
        access.require(Permission.STOCK_READ);
        if (size < 1 || size > CursorPage.MAX_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "size must be between 1 and " + CursorPage.MAX_SIZE);
        }
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "from must be before to");
        }
        if (query.godownId() != null) {
            access.requireGodown(query.godownId());
        }
        MovementPosition after = cursor == null || cursor.isBlank() ? null : decodeCursor(cursor);
        List<MovementRow> rows = stock.findMovements(access.businessId(), access.godownScope().all(),
                access.membershipId(), new MovementFilter(query.godownId(), query.productId(), query.type(),
                        query.from(), query.to()), after, size + 1);
        boolean more = rows.size() > size;
        List<MovementRow> page = more ? rows.subList(0, size) : rows;
        String next = more ? encodeCursor(page.getLast()) : null;
        return new CursorPage<>(page.stream().map(StockService::view).toList(), next);
    }

    // --- M05.S03 opening and inward ---------------------------------------------------------------

    /**
     * Posts opening or inward stock into one godown. Opening stock is refused for a product that
     * already has movements in the godown; inactive products cannot receive stock. Runs inside the
     * idempotent command's transaction, so a retried request posts nothing twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockDocumentView inward(BusinessAccess access, InwardCommand command) {
        access.require(Permission.STOCK_INWARD);
        if (command.type() != StockMovementType.OPENING && command.type() != StockMovementType.INWARD) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "source must be OPENING or INWARD");
        }
        requireGodown(access, command.godownId());
        String reference = text("reference", command.reference(), REFERENCE_MAX);
        String note = text("note", command.note(), NOTE_MAX);
        List<Line> lines = lines(access, command.lines(), true);
        return post(access, command.type(), command.godownId(), reference, null, note, lines);
    }

    // --- M05.S04 adjust ---------------------------------------------------------------------------

    /**
     * Adjusts stock in one godown by signed quantities, with a reason (and a note for
     * {@link AdjustmentReason#OTHER}). Refused when any line would take a balance below zero.
     * Inactive products may be adjusted, so their remaining stock can be written off.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockDocumentView adjust(BusinessAccess access, AdjustmentCommand command) {
        access.require(Permission.STOCK_ADJUST);
        if (command.reason() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "reason is required");
        }
        requireGodown(access, command.godownId());
        String note = text("note", command.note(), NOTE_MAX);
        if (command.reason() == AdjustmentReason.OTHER && note == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "note is required when reason is OTHER");
        }
        List<Line> lines = lines(access, command.lines(), false);
        return post(access, StockMovementType.ADJUSTMENT, command.godownId(), null, command.reason(), note, lines);
    }

    // --- M04 opening stock imports ----------------------------------------------------------------

    /** Which of the pairs already have stock movements, so opening stock can no longer be posted to them. */
    @Transactional(readOnly = true)
    public Set<StockKey> keysWithMovements(UUID businessId, Collection<StockKey> keys) {
        return stock.keysWithMovements(businessId, keys);
    }

    /**
     * Posts the opening stock of a validated import, one movement per row, attributed to the system
     * with the committing member as initiator. Runs inside the import's commit transaction; the
     * ledger re-checks every row under lock, and any failure rolls back the whole import.
     *
     * @return the movement of each row, in the order given
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> postImportedOpening(UUID businessId, UUID initiatorMembershipId, UUID importId,
            List<ImportedOpening> rows) {
        List<Posting> postings = rows.stream()
                .map(row -> new Posting(row.godownId(), row.product().id(), row.product().sku(),
                        StockMovementType.OPENING, row.quantity(), StockLedger.SOURCE_IMPORT, importId,
                        row.rowNumber()))
                .toList();
        List<Posted> posted = ledger.post(businessId, null, initiatorMembershipId, now(), postings);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ImportedOpening row = rows.get(i);
            Posted movement = posted.get(i);
            Map<String, AuditChange> changes = new LinkedHashMap<>();
            changes.put("importId", new AuditChange(null, importId));
            changes.put("rowNumber", new AuditChange(null, row.rowNumber()));
            changes.put("godownId", new AuditChange(null, row.godownId()));
            changes.put("productId", new AuditChange(null, row.product().id()));
            changes.put("quantity", new AuditChange(null, row.quantity().entered()));
            changes.put("unit", new AuditChange(null, row.quantity().unit().name()));
            changes.put("baseQuantity", new AuditChange(null, row.quantity().base()));
            changes.put("balance", new AuditChange(movement.balanceBefore(), movement.balanceAfter()));
            audit.append(AuditEvent.system(businessId, initiatorMembershipId, AuditSource.IMPORT, "stock.opening",
                    MOVEMENT, movement.movementId(), changes));
            ids.add(movement.movementId());
        }
        return ids;
    }

    // --- posting ----------------------------------------------------------------------------------

    /** A validated request line. */
    private record Line(StockProduct product, StockQuantity quantity) {
    }

    private StockDocumentView post(BusinessAccess access, StockMovementType type, UUID godownId, String reference,
            AdjustmentReason reason, String note, List<Line> lines) {
        UUID id = UUID.randomUUID();
        Instant now = now();
        stock.insertDocument(id, access.businessId(), type, godownId, reference, reason, note,
                access.membershipId(), now);
        List<Posting> postings = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            postings.add(new Posting(godownId, line.product().id(), line.product().sku(), type, line.quantity(),
                    StockLedger.SOURCE_DOCUMENT, id, i + 1));
        }
        List<Posted> posted = ledger.post(access.businessId(), access.membershipId(), access.membershipId(), now,
                postings);

        List<DocumentLineView> views = new ArrayList<>();
        List<Map<String, Object>> auditLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            Posted movement = posted.get(i);
            StockQuantity quantity = line.quantity();
            views.add(new DocumentLineView(i + 1, movement.movementId(), line.product().id(), line.product().sku(),
                    quantity.entered(), quantity.unit().name(), quantity.factor(), quantity.base(),
                    line.product().baseUnit().name(), movement.balanceAfter()));
            Map<String, Object> auditLine = new LinkedHashMap<>();
            auditLine.put("movementId", movement.movementId());
            auditLine.put("productId", line.product().id());
            auditLine.put("quantity", quantity.entered());
            auditLine.put("unit", quantity.unit().name());
            auditLine.put("baseQuantity", quantity.base());
            auditLine.put("balanceBefore", movement.balanceBefore());
            auditLine.put("balanceAfter", movement.balanceAfter());
            auditLines.add(auditLine);
        }
        Map<String, AuditChange> changes = new LinkedHashMap<>();
        changes.put("type", new AuditChange(null, type.name()));
        changes.put("godownId", new AuditChange(null, godownId));
        if (reference != null) {
            changes.put("reference", new AuditChange(null, reference));
        }
        if (reason != null) {
            changes.put("reason", new AuditChange(null, reason.name()));
        }
        if (note != null) {
            changes.put("note", new AuditChange(null, note));
        }
        changes.put("lines", new AuditChange(null, auditLines));
        String action = switch (type) {
            case OPENING -> "stock.opening";
            case INWARD -> "stock.inward";
            case ADJUSTMENT -> "stock.adjust";
        };
        audit.append(AuditEvent.manual(access.businessId(), access.membershipId(), action, DOCUMENT, id, changes));
        return new StockDocumentView(id, type, godownId, reference, reason, note, access.membershipId(), now, views);
    }

    /** A godown of another business is not found; one of this business outside the scope is denied. */
    private void requireGodown(BusinessAccess access, UUID godownId) {
        if (godownId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "godownId is required");
        }
        if (godowns.findName(access.businessId(), godownId).isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Godown not found");
        }
        access.requireGodown(godownId);
    }

    /**
     * Checks the lines: 1 to {@value #MAX_LINES}, each product of this business at most once, a unit
     * it counts in, and a quantity that is positive ({@code inward}) or non-zero (adjustments).
     */
    private List<Line> lines(BusinessAccess access, List<LineInput> inputs, boolean inward) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_LINES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "lines must have 1 to " + MAX_LINES + " items");
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
        Map<UUID, StockProduct> found = products.findByIds(access.businessId(), firstLine.keySet());
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            LineInput input = inputs.get(i);
            StockProduct product = found.get(input.productId());
            if (product == null) {
                throw lineError(i, "product not found");
            }
            if (inward && product.status() != ProductService.Status.ACTIVE) {
                throw new ApiException(ErrorCode.PRODUCT_INACTIVE,
                        "lines[" + i + "]: product " + product.sku() + " is inactive and cannot receive stock");
            }
            int sign = input.quantity().signum();
            if (inward ? sign <= 0 : sign == 0) {
                throw lineError(i, inward ? "quantity must be greater than zero" : "quantity must not be zero");
            }
            Unit unit = null;
            if (input.unit() != null && !input.unit().isBlank()) {
                unit = ProductRules.unit(input.unit());
                if (unit == null) {
                    throw lineError(i, "unit must be one of " + List.of(Unit.values()));
                }
            }
            switch (StockQuantity.convert(product, input.quantity(), unit)) {
                case Converted converted -> lines.add(new Line(product, converted.quantity()));
                case Problem problem -> throw lineError(i, problem.message());
            }
        }
        return lines;
    }

    /** Database precision, so a posted document reads back exactly as returned. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException lineError(int index, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "lines[" + index + "]: " + message);
    }

    /** Stripped text, {@code null} when blank. */
    private static String text(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " must be at most " + max + " characters");
        }
        return stripped;
    }

    private static MovementView view(MovementRow row) {
        return new MovementView(row.id(), row.type(), row.godownId(), row.productId(), row.sku(),
                ProductRules.plain(row.quantity()), row.baseUnit(), ProductRules.plain(row.enteredQuantity()),
                row.enteredUnit(), ProductRules.plain(row.unitFactor()), ProductRules.plain(row.balanceAfter()),
                new MovementSource(row.sourceType(), row.sourceId(), row.sourceLine()),
                new MovementActor(row.actorType(), row.actorMembershipId()), row.initiatorMembershipId(),
                row.reference(), row.reason(), row.note(), row.postedAt());
    }

    // --- cursor -----------------------------------------------------------------------------------

    static String encodeCursor(MovementRow last) {
        String raw = last.postedAt() + "|" + last.seq();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static MovementPosition decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            return new MovementPosition(Instant.parse(raw.substring(0, bar)), Long.parseLong(raw.substring(bar + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException | DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "cursor is invalid");
        }
    }
}
