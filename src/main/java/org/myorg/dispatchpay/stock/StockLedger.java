package org.myorg.dispatchpay.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.myorg.dispatchpay.catalog.ProductRules;
import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import org.myorg.dispatchpay.stock.StockRepository.LockedBalance;
import org.myorg.dispatchpay.stock.StockRepository.NewMovement;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts movements and updates balances atomically, enforcing the ledger invariants whoever posts:
 * a balance never goes below zero, and opening stock is only posted to a product and godown with no
 * movement yet. Every balance touched is locked first, in one global key order, so concurrent
 * postings serialize per key and cannot deadlock; the checks run against the locked balances.
 */
@Component
class StockLedger {

    static final String SOURCE_DOCUMENT = "STOCK_DOCUMENT";
    static final String SOURCE_IMPORT = "IMPORT";

    /**
     * Lock order: by godown, then product, comparing ids as PostgreSQL orders uuids (byte-wise,
     * which is the order of their canonical strings). Every posting path must use it.
     */
    static final Comparator<StockKey> LOCK_ORDER = Comparator
            .comparing((StockKey key) -> key.godownId().toString())
            .thenComparing(key -> key.productId().toString());

    /**
     * One movement to post.
     *
     * @param label    how errors name the product (its SKU)
     * @param quantity signed in the base unit for adjustments, otherwise positive
     */
    record Posting(
            UUID godownId,
            UUID productId,
            String label,
            StockMovementType type,
            StockQuantity quantity,
            String sourceType,
            UUID sourceId,
            int sourceLine) {

        StockKey key() {
            return new StockKey(godownId, productId);
        }
    }

    record Posted(UUID movementId, long seq, BigDecimal balanceBefore, BigDecimal balanceAfter) {
    }

    private final StockRepository stock;

    StockLedger(StockRepository stock) {
        this.stock = stock;
    }

    /**
     * Posts every posting or, by throwing, none: the caller's transaction rolls back.
     *
     * @param actorMembershipId the member posting it; {@code null} when the system posts on behalf
     *                          of {@code initiatorMembershipId} (an import)
     * @return the posted movements, in the order given
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Posted> post(UUID businessId, UUID actorMembershipId, UUID initiatorMembershipId, Instant now,
            List<Posting> postings) {
        Map<StockKey, LockedBalance> locked = new TreeMap<>(LOCK_ORDER);
        postings.forEach(posting -> locked.put(posting.key(), null));
        locked.replaceAll((key, ignored) -> stock.lockBalance(businessId, key, now));

        Map<StockKey, BigDecimal> balances = new HashMap<>();
        locked.forEach((key, balance) -> balances.put(key, balance.quantity()));
        List<Posted> posted = new ArrayList<>();
        for (Posting posting : postings) {
            StockKey key = posting.key();
            BigDecimal before = balances.get(key);
            if (posting.type() == StockMovementType.OPENING
                    && (locked.get(key).lastMovementSeq() != null || postedEarlier(postings, posted.size(), key))) {
                throw new ApiException(ErrorCode.OPENING_STOCK_EXISTS, "Product " + posting.label()
                        + " already has stock movements in this godown; opening stock can only be posted before"
                        + " any other movement. Post it as inward stock instead");
            }
            BigDecimal after = ProductRules.plain(before.add(posting.quantity().base()));
            if (after.signum() < 0) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Product " + posting.label() + " has only "
                        + ProductRules.plain(before).toPlainString() + " in stock in this godown; this would take it"
                        + " below zero");
            }
            if (!ProductRules.fitsDecimal(after)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "The stock of product " + posting.label()
                        + " would exceed " + ProductRules.MAX_INTEGER_DIGITS + " digits");
            }
            UUID id = UUID.randomUUID();
            StockQuantity quantity = posting.quantity();
            long seq = stock.insertMovement(new NewMovement(id, businessId, posting.godownId(), posting.productId(),
                    posting.type(), quantity.base(), quantity.entered(), quantity.unit().name(), quantity.factor(),
                    after, posting.sourceType(), posting.sourceId(), posting.sourceLine(), actorMembershipId,
                    initiatorMembershipId, now));
            stock.updateBalance(businessId, key, after, seq, now);
            balances.put(key, after);
            posted.add(new Posted(id, seq, ProductRules.plain(before), after));
        }
        return posted;
    }

    private static boolean postedEarlier(List<Posting> postings, int count, StockKey key) {
        return postings.subList(0, count).stream().anyMatch(earlier -> earlier.key().equals(key));
    }
}
