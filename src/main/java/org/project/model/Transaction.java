package org.project.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Transaction {
    private final String id;
    private final String receiptId;
    private String merchant;
    private String date;
    private String currency;
    private BigDecimal grandTotal;
    private List<TaxLine> taxes = Collections.emptyList();
    private List<LineItem> lineItems = Collections.emptyList();
    private ItemizeStatus itemizeStatus;
    /** Optimistic-locking version: starts at 1, +1 on every saved change. Also sent as the ETag. */
    private long version;

    public Transaction(String id, String receiptId) {
        this.id = id;
        this.receiptId = receiptId;
    }

    public String getId() { return id; }
    public String getReceiptId() { return receiptId; }
    public String getMerchant() { return merchant; }
    public void setMerchant(String merchant) { this.merchant = merchant; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }
    public List<TaxLine> getTaxes() { return taxes; }
    public void setTaxes(List<TaxLine> taxes) { this.taxes = Collections.unmodifiableList(new ArrayList<>(taxes)); }
    public List<LineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<LineItem> lineItems) { this.lineItems = Collections.unmodifiableList(new ArrayList<>(lineItems)); }
    public ItemizeStatus getItemizeStatus() { return itemizeStatus; }
    public void setItemizeStatus(ItemizeStatus itemizeStatus) { this.itemizeStatus = itemizeStatus; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    /** Independent copy, so callers never share mutable state with the store. Lists are already immutable. */
    public Transaction copy() {
        Transaction c = new Transaction(id, receiptId);
        c.merchant = merchant;
        c.date = date;
        c.currency = currency;
        c.grandTotal = grandTotal;
        c.taxes = taxes;
        c.lineItems = lineItems;
        c.itemizeStatus = itemizeStatus;
        c.version = version;
        return c;
    }
}
