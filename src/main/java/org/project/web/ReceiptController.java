package org.project.web;

import org.project.model.Receipt;
import org.project.model.Transaction;
import org.project.service.ReceiptService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.Map;

@RestController
public class ReceiptController {

    private final ReceiptService service;

    public ReceiptController(ReceiptService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Collections.singletonMap("status", "ok");
    }

    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) {
        Receipt receipt = service.upload(file);
        return Collections.singletonMap("receipt_id", receipt.getId());
    }

    @PostMapping("/receipts/{id}/process")
    public ResponseEntity<Transaction> process(@PathVariable String id) {
        Transaction tx = service.process(id);
        return TransactionController.withEtag(tx);
    }
}
