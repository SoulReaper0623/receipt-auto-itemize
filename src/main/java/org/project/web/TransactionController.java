package org.project.web;

import org.project.error.ApiException;
import org.project.error.ErrorCode;
import org.project.model.LineItem;
import org.project.model.Transaction;
import org.project.service.ReceiptService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transactions/{id}")
public class TransactionController {

    /** PATCH body: the full list of items the user wants (covers edit, merge and split). */
    public static class ItemsRequest {
        private List<LineItem> items;

        public List<LineItem> getItems() { return items; }
        public void setItems(List<LineItem> items) { this.items = items; }
    }

    private final ReceiptService service;

    public TransactionController(ReceiptService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Transaction> get(@PathVariable String id) {
        return withEtag(service.getTransaction(id));
    }

    @PostMapping("/itemize")
    public ResponseEntity<Transaction> itemize(@PathVariable String id) {
        Transaction tx = service.reItemize(id);
        return withEtag(tx);
    }

    @PatchMapping("/items")
    public ResponseEntity<Transaction> replaceItems(@PathVariable String id,
                                                    @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                    @RequestBody ItemsRequest request) {
        if (request.getItems() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "items is required");
        }
        for (LineItem item : request.getItems()) {
            if (!isValid(item)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "each item needs a description and an amount");
            }
        }
        Long expectedVersion = parseIfMatch(ifMatch);
        Transaction tx = service.replaceItems(id, request.getItems(), expectedVersion);
        return withEtag(tx);
    }

    private static boolean isValid(LineItem item) {
        if (item == null || item.getAmount() == null) {
            return false;
        }
        String description = item.getDescription();
        return description != null && !description.trim().isEmpty();
    }

    /** The ETag is the transaction version; send it back as If-Match to edit safely. */
    static ResponseEntity<Transaction> withEtag(Transaction tx) {
        String etag = String.valueOf(tx.getVersion());
        return ResponseEntity.ok().eTag(etag).body(tx);
    }

    /** Accepts "3", "\"3\"" or W/"3". Missing or "*" means "don't check". */
    static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.trim().isEmpty() || ifMatch.trim().equals("*")) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        try {
            return Long.parseLong(value.replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "If-Match must be the transaction ETag, e.g. \"3\"");
        }
    }
}
