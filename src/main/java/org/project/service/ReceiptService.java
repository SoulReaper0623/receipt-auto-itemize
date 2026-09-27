package org.project.service;

import org.project.error.ApiException;
import org.project.error.ErrorCode;
import org.project.error.ReconciliationException;
import org.project.model.*;
import org.project.ocr.OcrEngine;
import org.project.repository.ReceiptRepository;
import org.project.repository.TransactionRepository;
import org.project.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Orchestrates upload -> OCR -> parse -> reconcile (Facade).
 * Depends only on interfaces for OCR, file storage and persistence.
 */
@Service
public class ReceiptService {

    // Logs carry ids, statuses and counts only — never OCR text, merchant names, amounts or filenames.
    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

    private final ReceiptRepository receipts;
    private final TransactionRepository transactions;
    private final FileStorage fileStorage;
    private final OcrEngine ocr;
    private final UploadValidator uploadValidator;
    private final ReceiptParser parser;
    private final Reconciler reconciler;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public ReceiptService(ReceiptRepository receipts, TransactionRepository transactions, FileStorage fileStorage,
                          OcrEngine ocr, UploadValidator uploadValidator, ReceiptParser parser, Reconciler reconciler) {
        this.receipts = receipts;
        this.transactions = transactions;
        this.fileStorage = fileStorage;
        this.ocr = ocr;
        this.uploadValidator = uploadValidator;
        this.parser = parser;
        this.reconciler = reconciler;
    }

    public Receipt upload(MultipartFile file) {
        String ext = uploadValidator.validate(file);
        String id = Ids.newReceiptId();

        byte[] content = bytesOf(file);
        String storageKey = fileStorage.save(id + "." + ext, content);
        log.info("Receipt uploaded: receiptId={}, type={}, bytes={}", id, ext, content.length);
        Receipt receipt = new Receipt(id, file.getOriginalFilename(), storageKey);
        return receipts.save(receipt);
    }

    /**
     * OCR + parse run outside the lock (a real OCR call is slow); only the create-or-update is locked.
     * Re-reading the receipt inside the lock guarantees one transaction per receipt, even if two
     * process calls for the same receipt race.
     */
    public Transaction process(String receiptId) {
        Receipt uploaded = findReceipt(receiptId);
        byte[] content = fileStorage.load(uploaded.getStorageKey());
        String rawText = ocr.extractText(uploaded.getOriginalFilename(), content);
        ParsedReceipt parsed = parser.parse(rawText);

        synchronized (lockFor(receiptId)) {
            Receipt receipt = findReceipt(receiptId);
            // One transaction per receipt: create it the first time, update it after that.
            boolean created = receipt.getTransactionId() == null;
            Transaction tx;
            if (created) {
                tx = new Transaction(Ids.newTransactionId(), receiptId);
            } else {
                tx = getTransaction(receipt.getTransactionId());
            }
            tx.setMerchant(parsed.merchant());
            tx.setDate(parsed.date());
            tx.setCurrency(parsed.currency());
            tx.setGrandTotal(parsed.grandTotal());
            tx.setTaxes(parsed.taxes());
            applyItems(tx, parsed.lineItems());
            Transaction saved = saveNewVersion(tx);

            receipt.setRawOcrText(rawText);
            receipt.setTransactionId(saved.getId());
            receipts.save(receipt);
            String action = "updated";
            if (created) {
                action = "created";
            }
            log.info("Receipt processed: receiptId={}, transactionId={}, {}, version={}, ocrChars={}, taxes={}, items={}, itemizeStatus={}",
                    receiptId, saved.getId(), action, saved.getVersion(), rawText.length(),
                    saved.getTaxes().size(), saved.getLineItems().size(), saved.getItemizeStatus());
            if (saved.getGrandTotal() == null) {
                log.warn("No total found in OCR text: receiptId={}", receiptId);
            }
            return saved;
        }
    }

    public Transaction getTransaction(String id) {
        Optional<Transaction> tx = transactions.findById(id);
        if (!tx.isPresent()) {
            throw notFound(ErrorCode.TRANSACTION_NOT_FOUND, id, Ids.RECEIPT_PREFIX,
                    "this is a receipt id; call POST /receipts/{id}/process to get its transaction");
        }
        return tx.get();
    }

    /** Re-runs auto-itemize from the stored OCR text. Only line items change. */
    public Transaction reItemize(String transactionId) {
        String receiptId = getTransaction(transactionId).getReceiptId();
        synchronized (lockFor(receiptId)) {
            Transaction tx = getTransaction(transactionId);
            String raw = findReceipt(receiptId).getRawOcrText();
            applyItems(tx, parser.parse(raw).lineItems());
            Transaction saved = saveNewVersion(tx);
            log.info("Re-itemized from stored OCR: transactionId={}, version={}, items={}, itemizeStatus={}",
                    saved.getId(), saved.getVersion(), saved.getLineItems().size(), saved.getItemizeStatus());
            return saved;
        }
    }

    /**
     * User override. Rejected (nothing saved) if the new items don't reconcile, or if expectedVersion is stale.
     * @param expectedVersion from If-Match; null = don't check (last write wins)
     */
    public Transaction replaceItems(String transactionId, List<LineItem> items, Long expectedVersion) {
        String receiptId = getTransaction(transactionId).getReceiptId();
        synchronized (lockFor(receiptId)) {
            Transaction tx = getTransaction(transactionId);
            checkVersion(tx, expectedVersion);
            ReconciliationResult result = reconciler.check(tx.getGrandTotal(), tx.getTaxes(), items);
            if (!result.isReconciled()) {
                log.info("Item edit rejected, does not reconcile: transactionId={}, items={}", tx.getId(), items.size());
                throw new ReconciliationException(result);
            }
            applyItems(tx, items);
            Transaction saved = saveNewVersion(tx);
            log.info("Items replaced by user: transactionId={}, version={}, items={}, itemizeStatus={}",
                    saved.getId(), saved.getVersion(), items.size(), saved.getItemizeStatus());
            return saved;
        }
    }

    /** One lock per receipt: changes to the same receipt/transaction run one at a time, different receipts in parallel. */
    private Object lockFor(String receiptId) {
        return locks.computeIfAbsent(receiptId, k -> new Object());
    }

    private void checkVersion(Transaction tx, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != tx.getVersion()) {
            log.info("Stale edit rejected: transactionId={}, expectedVersion={}, currentVersion={}",
                    tx.getId(), expectedVersion, tx.getVersion());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("expected_version", expectedVersion);
            details.put("current_version", tx.getVersion());
            throw new ApiException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.defaultMessage(), details);
        }
    }

    private Transaction saveNewVersion(Transaction tx) {
        tx.setVersion(tx.getVersion() + 1);
        return transactions.save(tx);
    }

    private void applyItems(Transaction tx, List<LineItem> items) {
        tx.setLineItems(items);
        ItemizeStatus status = reconciler.status(tx.getGrandTotal(), tx.getTaxes(), items);
        tx.setItemizeStatus(status);
    }

    private Receipt findReceipt(String id) {
        Optional<Receipt> receipt = receipts.findById(id);
        if (!receipt.isPresent()) {
            throw notFound(ErrorCode.RECEIPT_NOT_FOUND, id, Ids.TRANSACTION_PREFIX,
                    "this is a transaction id; use it with /transactions/{id}");
        }
        return receipt.get();
    }

    /** 404, with a hint when the caller passed the other kind of id. */
    private static ApiException notFound(ErrorCode code, String id, String wrongPrefix, String hint) {
        String message = code.defaultMessage();
        if (id.startsWith(wrongPrefix)) {
            message = message + ": " + hint;
        }
        return new ApiException(code, message);
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
