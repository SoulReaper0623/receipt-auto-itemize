package org.project.repository;

import org.project.model.Transaction;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Stores and returns copies: no caller can change stored state without calling save. */
@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<String, Transaction> store = new ConcurrentHashMap<>();

    @Override
    public Transaction save(Transaction transaction) {
        store.put(transaction.getId(), transaction.copy());
        return transaction.copy();
    }

    @Override
    public Optional<Transaction> findById(String id) {
        Transaction stored = store.get(id);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(stored.copy());
    }
}
