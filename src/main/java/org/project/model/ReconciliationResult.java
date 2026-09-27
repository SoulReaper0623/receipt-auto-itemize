package org.project.model;

import java.math.BigDecimal;

/**
 * Outcome of checking line items against a transaction total.
 * expected = grand total, actual = items + taxes added on top, difference = expected - actual.
 * expected and difference are null when the receipt has no total.
 */
public final class ReconciliationResult {
    private final BigDecimal expected;
    private final BigDecimal actual;
    private final BigDecimal difference;
    private final boolean reconciled;

    public ReconciliationResult(BigDecimal expected, BigDecimal actual, BigDecimal difference, boolean reconciled) {
        this.expected = expected;
        this.actual = actual;
        this.difference = difference;
        this.reconciled = reconciled;
    }

    public BigDecimal getExpected() { return expected; }
    public BigDecimal getActual() { return actual; }
    public BigDecimal getDifference() { return difference; }
    public boolean isReconciled() { return reconciled; }
}
