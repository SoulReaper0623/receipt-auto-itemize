package org.project.model;

public class Receipt {
    private final String id;
    private final String originalFilename;
    private final String storageKey;
    private String rawOcrText;
    private String transactionId;

    public Receipt(String id, String originalFilename, String storageKey) {
        this.id = id;
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
    }

    public String getId() { return id; }
    public String getOriginalFilename() { return originalFilename; }
    public String getStorageKey() { return storageKey; }
    public String getRawOcrText() { return rawOcrText; }
    public void setRawOcrText(String rawOcrText) { this.rawOcrText = rawOcrText; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    /** Independent copy, so callers never share mutable state with the store. */
    public Receipt copy() {
        Receipt c = new Receipt(id, originalFilename, storageKey);
        c.rawOcrText = rawOcrText;
        c.transactionId = transactionId;
        return c;
    }
}
