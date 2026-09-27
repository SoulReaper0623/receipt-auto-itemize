package org.project.service;

import org.project.model.ItemizeStatus;
import org.project.model.LineItem;
import org.project.model.ReconciliationResult;
import org.project.model.TaxLine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Items reconcile when sum(items) + sum(exclusive taxes) == grand total (±0.01).
 * Inclusive taxes ("incl. VAT") are already inside item prices, so they are not added.
 * Never adjusts anything — it only reports.
 */
@Component
public class Reconciler {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    public ReconciliationResult check(BigDecimal grandTotal, List<TaxLine> taxes, List<LineItem> items) {
        BigDecimal itemSum = BigDecimal.ZERO;
        for (LineItem item : items) {
            itemSum = itemSum.add(item.getAmount());
        }
        // Only taxes added on top count; inclusive taxes are already inside the item prices.
        BigDecimal taxOnTop = BigDecimal.ZERO;
        for (TaxLine tax : taxes) {
            if (!tax.isInclusive()) {
                taxOnTop = taxOnTop.add(tax.getAmount());
            }
        }
        BigDecimal actual = itemSum.add(taxOnTop);
        if (grandTotal == null) {
            return new ReconciliationResult(null, actual, null, false);
        }
        BigDecimal difference = grandTotal.subtract(actual);
        // Allow up to 0.01 either way for rounding on the receipt.
        boolean withinTolerance = difference.abs().compareTo(TOLERANCE) <= 0;
        return new ReconciliationResult(grandTotal, actual, difference, withinTolerance);
    }

    public ItemizeStatus status(BigDecimal grandTotal, List<TaxLine> taxes, List<LineItem> items) {
        if (grandTotal == null) {
            return ItemizeStatus.FAILED;
        }
        if (items.isEmpty()) {
            return ItemizeStatus.NEEDS_REVIEW;
        }
        ReconciliationResult result = check(grandTotal, taxes, items);
        if (result.isReconciled()) {
            return ItemizeStatus.COMPLETE;
        }
        return ItemizeStatus.NEEDS_REVIEW;
    }
}
