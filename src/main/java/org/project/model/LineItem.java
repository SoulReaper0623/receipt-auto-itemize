package org.project.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Objects;

public final class LineItem {
    private final String description;
    private final BigDecimal amount;

    @JsonCreator
    public LineItem(@JsonProperty("description") String description, @JsonProperty("amount") BigDecimal amount) {
        this.description = description;
        this.amount = amount;
    }

    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LineItem)) return false;
        LineItem l = (LineItem) o;
        return Objects.equals(description, l.description) && Objects.equals(amount, l.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, amount);
    }

    @Override
    public String toString() {
        return "LineItem{" + description + ", " + amount + "}";
    }
}
