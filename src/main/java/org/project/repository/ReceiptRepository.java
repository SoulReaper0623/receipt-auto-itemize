package org.project.repository;

import org.project.model.Receipt;

import java.util.Optional;

public interface ReceiptRepository {

    Receipt save(Receipt receipt);

    Optional<Receipt> findById(String id);
}
