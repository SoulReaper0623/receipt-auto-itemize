package org.project.model;

import java.math.BigDecimal;
import java.util.List;

/** Structured fields extracted from raw OCR text. */
public final class ParsedReceipt {
    private final String merchant;
    private final String date;
    private final String currency;
    private final BigDecimal grandTotal;
    private final List<TaxLine> taxes;
    private final List<LineItem> lineItems;

    public ParsedReceipt(String merchant, String date, String currency, BigDecimal grandTotal,
                         List<TaxLine> taxes, List<LineItem> lineItems) {
        this.merchant = merchant;
        this.date = date;
        this.currency = currency;
        this.grandTotal = grandTotal;
        this.taxes = taxes;
        this.lineItems = lineItems;
    }

    public String merchant() { return merchant; }
    public String date() { return date; }
    public String currency() { return currency; }
    public BigDecimal grandTotal() { return grandTotal; }
    public List<TaxLine> taxes() { return taxes; }
    public List<LineItem> lineItems() { return lineItems; }
}
