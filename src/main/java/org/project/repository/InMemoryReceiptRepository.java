package org.project.repository;

import org.project.model.Receipt;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Stores and returns copies: no caller can change stored state without calling save. */
@Repository
public class InMemoryReceiptRepository implements ReceiptRepository {

    private final Map<String, Receipt> store = new ConcurrentHashMap<>();

    @Override
    public Receipt save(Receipt receipt) {
        store.put(receipt.getId(), receipt.copy());
        return receipt.copy();
    }

    @Override
    public Optional<Receipt> findById(String id) {
        Receipt stored = store.get(id);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(stored.copy());
    }
}
