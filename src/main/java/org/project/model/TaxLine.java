package org.project.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One tax row. {@code inclusive} = the amount is already inside the item prices
 * ("incl. VAT"), so it must not be added on top when reconciling.
 */
public final class TaxLine {
    private final String name;
    private final BigDecimal rate;
    private final BigDecimal amount;
    private final boolean inclusive;

    public TaxLine(String name, BigDecimal rate, BigDecimal amount, boolean inclusive) {
        this.name = name;
        this.rate = rate;
        this.amount = amount;
        this.inclusive = inclusive;
    }

    public String getName() { return name; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getAmount() { return amount; }
    public boolean isInclusive() { return inclusive; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaxLine)) return false;
        TaxLine t = (TaxLine) o;
        return inclusive == t.inclusive && Objects.equals(name, t.name)
                && Objects.equals(rate, t.rate) && Objects.equals(amount, t.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, rate, amount, inclusive);
    }

    @Override
    public String toString() {
        return "TaxLine{" + name + ", rate=" + rate + ", amount=" + amount + ", inclusive=" + inclusive + "}";
    }
}
