package org.myorg.dispatchpay.stock;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.myorg.dispatchpay.stock.StockRepository.Variance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * M05.S05: compares every balance with the sum of its signed movements, across all businesses. A
 * variance opens a discrepancy (logged at ERROR, counted by the
 * {@value #OPEN_DISCREPANCIES} gauge) that stays open while it is seen and is resolved once a later
 * run finds the pair in agreement. Neither balances nor movements are ever rewritten: correcting
 * stock is a human decision, made through an audited adjustment.
 */
@Component
public class StockReconciler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciler.class);

    static final String OPEN_DISCREPANCIES = "dispatchpay.stock.discrepancies.open";

    /** @param skipped another instance was reconciling, so this run did nothing */
    public record Report(boolean skipped, int variances, int opened, int resolved, long open) {
    }

    private final StockRepository stock;
    private final Clock clock;
    private final AtomicLong open = new AtomicLong();

    StockReconciler(StockRepository stock, Clock clock, MeterRegistry meters) {
        this.stock = stock;
        this.clock = clock;
        Gauge.builder(OPEN_DISCREPANCIES, open, AtomicLong::get)
                .description("Stock balances that disagree with their movements and are not resolved yet")
                .register(meters);
    }

    @Transactional
    public Report reconcile() {
        if (!stock.tryReconciliationLock()) {
            return new Report(true, 0, 0, 0, open.get());
        }
        Instant now = clock.instant();
        List<Variance> variances = stock.findVariances();
        Map<UUID, List<Variance>> byBusiness = new LinkedHashMap<>();
        variances.forEach(variance ->
                byBusiness.computeIfAbsent(variance.businessId(), id -> new ArrayList<>()).add(variance));
        Set<UUID> businesses = new LinkedHashSet<>(byBusiness.keySet());
        businesses.addAll(stock.businessesWithOpenDiscrepancies());

        int opened = 0;
        int resolved = 0;
        for (UUID businessId : businesses) {
            Map<StockKey, UUID> existing = stock.lockOpenDiscrepancies(businessId);
            for (Variance variance : byBusiness.getOrDefault(businessId, List.of())) {
                StockKey key = new StockKey(variance.godownId(), variance.productId());
                UUID id = existing.remove(key);
                if (id != null) {
                    stock.refreshDiscrepancy(id, variance, now);
                    continue;
                }
                stock.openDiscrepancy(UUID.randomUUID(), variance, now);
                opened++;
                log.error("Stock discrepancy in business {}: godown {} product {} has balance {} but its movements"
                                + " sum to {}", businessId, variance.godownId(), variance.productId(),
                        variance.balance(), variance.movements());
            }
            for (Map.Entry<StockKey, UUID> gone : existing.entrySet()) {
                stock.resolveDiscrepancy(gone.getValue(), now);
                resolved++;
                log.info("Stock discrepancy {} in business {} (godown {} product {}) no longer seen; resolved",
                        gone.getValue(), businessId, gone.getKey().godownId(), gone.getKey().productId());
            }
        }
        open.set(stock.countOpenDiscrepancies());
        if (!variances.isEmpty()) {
            log.warn("Stock reconciliation found {} variances ({} new); {} discrepancies open", variances.size(),
                    opened, open.get());
        }
        return new Report(false, variances.size(), opened, resolved, open.get());
    }
}
