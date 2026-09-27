package org.project.model;

import java.util.UUID;

/**
 * Prefixed ids so the kind is obvious at a glance: rcpt_… for receipts, txn_… for transactions.
 * The random part is a random (v4) UUID without dashes.
 */
public final class Ids {

    public static final String RECEIPT_PREFIX = "rcpt_";
    public static final String TRANSACTION_PREFIX = "txn_";

    private Ids() {
    }

    public static String newReceiptId() {
        return RECEIPT_PREFIX + random();
    }

    public static String newTransactionId() {
        return TRANSACTION_PREFIX + random();
    }

    private static String random() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
