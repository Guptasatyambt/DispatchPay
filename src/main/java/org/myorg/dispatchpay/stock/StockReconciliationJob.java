package org.myorg.dispatchpay.stock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link StockReconciler} every {@code dispatchpay.stock.reconciliation-interval}. */
@Component
class StockReconciliationJob {

    private final StockReconciler reconciler;

    StockReconciliationJob(StockReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${dispatchpay.stock.reconciliation-interval}",
            initialDelayString = "${dispatchpay.stock.reconciliation-interval}")
    public void run() {
        reconciler.reconcile();
    }
}
