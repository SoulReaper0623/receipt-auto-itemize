package org.project;

import org.junit.jupiter.api.Test;
import org.project.error.ApiException;
import org.project.error.ErrorCode;
import org.project.error.ReconciliationException;
import org.project.model.ItemizeStatus;
import org.project.model.LineItem;
import org.project.model.Receipt;
import org.project.model.Transaction;
import org.project.ocr.OcrEngine;
import org.project.repository.InMemoryReceiptRepository;
import org.project.repository.InMemoryTransactionRepository;
import org.project.service.ReceiptParser;
import org.project.service.ReceiptService;
import org.project.service.Reconciler;
import org.project.service.UploadValidator;
import org.project.storage.FileStorage;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ReceiptService wired by hand with fakes: no Spring, no disk, no fixture files. */
class ReceiptServiceTest {

    /** OCR fake: returns whatever text the test sets, and counts calls. */
    static class FakeOcr implements OcrEngine {
        String text = "";
        int calls = 0;

        @Override
        public String extractText(String originalFilename, byte[] content) {
            calls++;
            return text;
        }
    }

    static class InMemoryFileStorage implements FileStorage {
        final Map<String, byte[]> files = new HashMap<>();

        @Override
        public String save(String key, byte[] content) {
            files.put(key, content);
            return key;
        }

        @Override
        public byte[] load(String key) {
            return files.get(key);
        }
    }

    private static final String RECEIPT =
            "MERCHANT: Test Shop\nDATE: 2026-01-01\nCURRENCY: EUR\n"
                    + "Tea   2.00\nCake   3.00\nVAT 10%   0.50\nTOTAL   5.50\n";

    private final FakeOcr ocr = new FakeOcr();
    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final ReceiptService service = new ReceiptService(
            new InMemoryReceiptRepository(), new InMemoryTransactionRepository(), storage,
            ocr, new UploadValidator(), new ReceiptParser(), new Reconciler());

    private Receipt uploadPng() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        return service.upload(new MockMultipartFile("file", "photo.png", "image/png", png));
    }

    @Test
    void uploadStoresFileUnderServerGeneratedKey() {
        Receipt receipt = uploadPng();

        assertThat(receipt.getStorageKey()).isEqualTo(receipt.getId() + ".png");
        assertThat(storage.files).containsKey(receipt.getStorageKey());
    }

    @Test
    void processUsesWhateverOcrEngineIsPluggedIn() {
        ocr.text = RECEIPT;

        Transaction tx = service.process(uploadPng().getId());

        assertThat(tx.getMerchant()).isEqualTo("Test Shop");
        assertThat(tx.getGrandTotal()).isEqualByComparingTo("5.50");
        assertThat(tx.getLineItems()).hasSize(2);
        assertThat(tx.getItemizeStatus()).isEqualTo(ItemizeStatus.COMPLETE);
    }

    @Test
    void reItemizeUsesStoredOcrTextNotTheOcrEngine() {
        ocr.text = RECEIPT;
        Transaction tx = service.process(uploadPng().getId());

        ocr.text = "garbage";
        Transaction again = service.reItemize(tx.getId());

        assertThat(ocr.calls).isEqualTo(1);
        assertThat(again.getId()).isEqualTo(tx.getId());
        assertThat(again.getLineItems()).hasSize(2);
    }

    @Test
    void emptyOcrTextEndsAsFailed() {
        ocr.text = "";

        assertThat(service.process(uploadPng().getId()).getItemizeStatus()).isEqualTo(ItemizeStatus.FAILED);
    }

    @Test
    void rejectedEditLeavesItemsUntouched() {
        ocr.text = RECEIPT;
        Transaction tx = service.process(uploadPng().getId());

        assertThatThrownBy(() -> service.replaceItems(tx.getId(),
                Collections.singletonList(new LineItem("Tea", new BigDecimal("2.00"))), null))
                .isInstanceOf(ReconciliationException.class);
        assertThat(service.getTransaction(tx.getId()).getLineItems()).hasSize(2);
    }

    // ---- Concurrency ----

    private static final List<LineItem> FIXED_ITEMS = Arrays.asList(
            new LineItem("Tea", new BigDecimal("2.00")), new LineItem("Big cake", new BigDecimal("3.00")));

    @Test
    void versionStartsAtOneAndGoesUpOnEveryChange() {
        ocr.text = RECEIPT;
        Receipt receipt = uploadPng();

        Transaction tx = service.process(receipt.getId());
        assertThat(tx.getVersion()).isEqualTo(1);
        assertThat(service.replaceItems(tx.getId(), FIXED_ITEMS, 1L).getVersion()).isEqualTo(2);
        assertThat(service.reItemize(tx.getId()).getVersion()).isEqualTo(3);
        assertThat(service.process(receipt.getId()).getVersion()).isEqualTo(4);
    }

    @Test
    void staleVersionIsRejectedAndNothingChanges() {
        ocr.text = RECEIPT;
        Transaction tx = service.process(uploadPng().getId());
        service.replaceItems(tx.getId(), FIXED_ITEMS, 1L);

        List<LineItem> other = Collections.singletonList(new LineItem("Everything", new BigDecimal("5.00")));
        assertThatThrownBy(() -> service.replaceItems(tx.getId(), other, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        assertThat(service.getTransaction(tx.getId()).getLineItems()).isEqualTo(FIXED_ITEMS);
    }

    @Test
    void changingAReturnedObjectDoesNotChangeStoredState() {
        ocr.text = RECEIPT;
        Transaction tx = service.process(uploadPng().getId());

        tx.setLineItems(Collections.<LineItem>emptyList());
        service.getTransaction(tx.getId()).setGrandTotal(BigDecimal.ZERO);

        Transaction stored = service.getTransaction(tx.getId());
        assertThat(stored.getLineItems()).hasSize(2);
        assertThat(stored.getGrandTotal()).isEqualByComparingTo("5.50");
    }

    @Test
    void concurrentEditsWithSameVersionOnlyOneWins() throws Exception {
        ocr.text = RECEIPT;
        final String txId = service.process(uploadPng().getId()).getId();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    start.await();
                    try {
                        service.replaceItems(txId, FIXED_ITEMS, 1L);
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                }
            }));
        }
        start.countDown();
        int wins = 0;
        for (Future<Boolean> r : results) {
            if (r.get(10, TimeUnit.SECONDS)) {
                wins++;
            }
        }
        pool.shutdown();

        assertThat(wins).isEqualTo(1);
        assertThat(service.getTransaction(txId).getVersion()).isEqualTo(2);
    }

    @Test
    void concurrentEditsWithoutVersionAreSerializedNotLost() throws Exception {
        ocr.text = RECEIPT;
        final String txId = service.process(uploadPng().getId()).getId();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    start.await();
                    service.replaceItems(txId, FIXED_ITEMS, null);
                    return null;
                }
            }));
        }
        start.countDown();
        for (Future<?> r : results) {
            r.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // Every update was applied one at a time: 1 (process) + 16 edits.
        assertThat(service.getTransaction(txId).getVersion()).isEqualTo(1 + threads);
    }

    @Test
    void concurrentProcessOfSameReceiptCreatesOneTransaction() throws Exception {
        ocr.text = RECEIPT;
        final String receiptId = uploadPng().getId();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    start.await();
                    return service.process(receiptId).getId();
                }
            }));
        }
        start.countDown();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Future<String> r : results) {
            ids.add(r.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(service.getTransaction(ids.iterator().next()).getVersion()).isEqualTo(threads);
    }
}
