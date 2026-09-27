package org.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.upload-dir=target/test-uploads")
class ApiTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private String upload(String filename, String contentType, byte[] body) throws Exception {
        String res = mvc.perform(multipart("/receipts").file(new MockMultipartFile("file", filename, contentType, body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("receipt_id").asText();
    }

    private JsonNode process(String receiptId) throws Exception {
        String res = mvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(Paths.get("task-a/fixtures/task-a", name));
    }

    @Test
    void cleanReceiptEndToEnd() throws Exception {
        String receiptId = upload("receipt-clean.txt", "text/plain", fixture("receipt-clean.txt"));
        String txId = process(receiptId).get("id").asText();

        mvc.perform(get("/transactions/{id}", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt_id").value(receiptId))
                .andExpect(jsonPath("$.merchant").value("Cafe Mitte"))
                .andExpect(jsonPath("$.grand_total").value(17.85))
                .andExpect(jsonPath("$.taxes[0].name").value("VAT"))
                .andExpect(jsonPath("$.taxes[0].rate").value(0.19))
                .andExpect(jsonPath("$.line_items", hasSize(3)))
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"));
    }

    @Test
    void reprocessAndReitemizeKeepOneTransaction() throws Exception {
        String receiptId = upload("receipt-clean.txt", "text/plain", fixture("receipt-clean.txt"));
        String txId = process(receiptId).get("id").asText();

        // Processing again updates the same transaction.
        org.assertj.core.api.Assertions.assertThat(process(receiptId).get("id").asText()).isEqualTo(txId);

        mvc.perform(post("/transactions/{id}/itemize", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txId))
                .andExpect(jsonPath("$.line_items", hasSize(3)));
    }

    @Test
    void imageUploadUsesStubOcrByFilename() throws Exception {
        String receiptId = upload("receipt-mismatch.png", "image/png", PNG_MAGIC);

        mvc.perform(post("/receipts/{id}/process", receiptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grand_total").value(18.50))
                .andExpect(jsonPath("$.line_items", hasSize(2)))
                .andExpect(jsonPath("$.itemize_status").value("NEEDS_REVIEW"));
    }

    @Test
    void patchThatDoesNotReconcileIs409AndChangesNothing() throws Exception {
        String receiptId = upload("receipt-clean.txt", "text/plain", fixture("receipt-clean.txt"));
        String txId = process(receiptId).get("id").asText();

        mvc.perform(patch("/transactions/{id}/items", txId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"description\":\"Espresso\",\"amount\":3.50}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEMS_DO_NOT_RECONCILE"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.details.expected").value(17.85))
                .andExpect(jsonPath("$.details.actual").value(6.35))
                .andExpect(jsonPath("$.details.difference").value(11.50));

        mvc.perform(get("/transactions/{id}", txId))
                .andExpect(jsonPath("$.line_items", hasSize(3)))
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"));
    }

    @Test
    void patchThatReconcilesIsSavedAsComplete() throws Exception {
        String receiptId = upload("receipt-mismatch.txt", "text/plain", fixture("receipt-mismatch.txt"));
        String txId = process(receiptId).get("id").asText();

        // User fixes it: 4.00 + 12.60 + 1.90 VAT = 18.50
        mvc.perform(patch("/transactions/{id}/items", txId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"description\":\"Water\",\"amount\":4.00},"
                                + "{\"description\":\"Snacks\",\"amount\":12.60}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.line_items", hasSize(2)))
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"));
    }

    @Test
    void taxFlagIsExposedInJson() throws Exception {
        String cleanTx = process(upload("receipt-clean.txt", "text/plain", fixture("receipt-clean.txt"))).get("id").asText();
        String taxiTx = process(upload("receipt-tax-only.txt", "text/plain", fixture("receipt-tax-only.txt"))).get("id").asText();

        mvc.perform(get("/transactions/{id}", cleanTx)).andExpect(jsonPath("$.taxes[0].inclusive").value(false));
        mvc.perform(get("/transactions/{id}", taxiTx)).andExpect(jsonPath("$.taxes[0].inclusive").value(true));
    }

    @Test
    void taxiPatchWithGrossFareIsAcceptedBecauseVatIsInclusive() throws Exception {
        String txId = process(upload("receipt-tax-only.txt", "text/plain", fixture("receipt-tax-only.txt"))).get("id").asText();

        // 24.00 already contains the 3.83 VAT, so it must not be added again.
        mvc.perform(patch("/transactions/{id}/items", txId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"description\":\"Trip fare\",\"amount\":24.00}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemize_status").value("COMPLETE"));
    }

    @Test
    void taxiPatchWithNetFareIs409() throws Exception {
        String txId = process(upload("receipt-tax-only.txt", "text/plain", fixture("receipt-tax-only.txt"))).get("id").asText();

        // 20.17 = 24.00 - 3.83; with inclusive VAT the items must add up to the full 24.00.
        mvc.perform(patch("/transactions/{id}/items", txId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"description\":\"Trip fare (net)\",\"amount\":20.17}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEMS_DO_NOT_RECONCILE"))
                .andExpect(jsonPath("$.details.expected").value(24.00))
                .andExpect(jsonPath("$.details.actual").value(20.17))
                .andExpect(jsonPath("$.details.difference").value(3.83));
    }

    // ---- Error codes: each maps to one HTTP status and one body shape ----

    private static ResultMatcher error(String code, int httpStatus) {
        return ResultMatcher.matchAll(
                status().is(httpStatus),
                jsonPath("$.code").value(code),
                jsonPath("$.status").value(httpStatus),
                jsonPath("$.message").isNotEmpty(),
                jsonPath("$.request_id").isNotEmpty(),
                header().string("X-Request-Id", org.hamcrest.Matchers.notNullValue()));
    }

    private String cleanTransaction() throws Exception {
        return process(upload("receipt-clean.txt", "text/plain", fixture("receipt-clean.txt"))).get("id").asText();
    }

    @Test
    void disallowedFileTypeIs415() throws Exception {
        mvc.perform(multipart("/receipts").file(new MockMultipartFile("file", "evil.exe", "application/octet-stream", new byte[]{1})))
                .andExpect(error("UNSUPPORTED_FILE_TYPE", 415));
    }

    @Test
    void fileContentNotMatchingExtensionIs415() throws Exception {
        // Extension says PNG but the bytes are not a PNG.
        mvc.perform(multipart("/receipts").file(new MockMultipartFile("file", "fake.png", "image/png", "hello".getBytes())))
                .andExpect(error("FILE_CONTENT_MISMATCH", 415));
        // Extension and bytes are fine, but the declared content type is a different type.
        mvc.perform(multipart("/receipts").file(new MockMultipartFile("file", "a.txt", "image/png", "hi".getBytes())))
                .andExpect(error("FILE_CONTENT_MISMATCH", 415));
    }

    @Test
    void nonMultipartUploadIs415() throws Exception {
        mvc.perform(post("/receipts").contentType(MediaType.TEXT_PLAIN).content("MERCHANT: x"))
                .andExpect(error("UNSUPPORTED_MEDIA_TYPE", 415));
    }

    @Test
    void multipartWithoutFileFieldIs400() throws Exception {
        mvc.perform(multipart("/receipts").file(new MockMultipartFile("wrongField", "a.txt", "text/plain", "x".getBytes())))
                .andExpect(error("FILE_REQUIRED", 400));
        mvc.perform(multipart("/receipts").file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[0])))
                .andExpect(error("FILE_REQUIRED", 400));
    }

    @Test
    void badFileNameIs400() throws Exception {
        mvc.perform(multipart("/receipts").file(new MockMultipartFile("file", "../../etc/passwd.txt", "text/plain", "x".getBytes())))
                .andExpect(error("INVALID_FILE_NAME", 400));
    }

    @Test
    void invalidPatchBodyIs400() throws Exception {
        String txId = cleanTransaction();
        mvc.perform(patch("/transactions/{id}/items", txId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"description\":\"No amount\"}]}"))
                .andExpect(error("VALIDATION_FAILED", 400));
        mvc.perform(patch("/transactions/{id}/items", txId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(error("VALIDATION_FAILED", 400));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(patch("/transactions/{id}/items", cleanTransaction()).contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(error("MALFORMED_REQUEST", 400));
    }

    @Test
    void patchWithNonJsonBodyIs415() throws Exception {
        mvc.perform(patch("/transactions/{id}/items", cleanTransaction()).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(error("UNSUPPORTED_MEDIA_TYPE", 415));
    }

    @Test
    void unknownIdsAre404() throws Exception {
        mvc.perform(get("/transactions/nope")).andExpect(error("TRANSACTION_NOT_FOUND", 404));
        mvc.perform(post("/transactions/nope/itemize")).andExpect(error("TRANSACTION_NOT_FOUND", 404));
        mvc.perform(post("/receipts/nope/process")).andExpect(error("RECEIPT_NOT_FOUND", 404));
    }

    @Test
    void idsArePrefixedByKind() throws Exception {
        String receiptId = upload("receipt-clean.txt", "text/plain", fixture("receipt-clean.txt"));
        String txId = process(receiptId).get("id").asText();

        org.assertj.core.api.Assertions.assertThat(receiptId).matches("rcpt_[0-9a-f]{32}");
        org.assertj.core.api.Assertions.assertThat(txId).matches("txn_[0-9a-f]{32}");
    }

    @Test
    void mixedUpIdsGetAHelpfulNotFound() throws Exception {
        String receiptId = upload("receipt-clean.txt", "text/plain", fixture("receipt-clean.txt"));
        String txId = process(receiptId).get("id").asText();

        mvc.perform(get("/transactions/{id}", receiptId))
                .andExpect(error("TRANSACTION_NOT_FOUND", 404))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("receipt id")));
        mvc.perform(post("/receipts/{id}/process", txId))
                .andExpect(error("RECEIPT_NOT_FOUND", 404))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("transaction id")));
    }

    @Test
    void transactionResponsesCarryVersionAndEtag() throws Exception {
        String txId = cleanTransaction();

        mvc.perform(get("/transactions/{id}", txId))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(header().string("ETag", "\"1\""));
    }

    @Test
    void patchWithCurrentIfMatchSucceedsAndBumpsVersion() throws Exception {
        String txId = cleanTransaction();

        mvc.perform(patch("/transactions/{id}/items", txId).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"description\":\"All\",\"amount\":15.00}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(header().string("ETag", "\"2\""));
    }

    @Test
    void patchWithStaleIfMatchIs412AndChangesNothing() throws Exception {
        String txId = cleanTransaction();
        mvc.perform(post("/transactions/{id}/itemize", txId)).andExpect(status().isOk()); // now version 2

        mvc.perform(patch("/transactions/{id}/items", txId).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"description\":\"All\",\"amount\":15.00}]}"))
                .andExpect(error("VERSION_CONFLICT", 412))
                .andExpect(jsonPath("$.details.expected_version").value(1))
                .andExpect(jsonPath("$.details.current_version").value(2));

        mvc.perform(get("/transactions/{id}", txId)).andExpect(jsonPath("$.line_items", hasSize(3)));
    }

    @Test
    void badIfMatchIs400() throws Exception {
        mvc.perform(patch("/transactions/{id}/items", cleanTransaction()).header("If-Match", "\"abc\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"description\":\"All\",\"amount\":15.00}]}"))
                .andExpect(error("VALIDATION_FAILED", 400));
    }

    @Test
    void unknownRouteIs404() throws Exception {
        mvc.perform(get("/nope")).andExpect(error("ROUTE_NOT_FOUND", 404));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mvc.perform(get("/receipts")).andExpect(error("METHOD_NOT_ALLOWED", 405));
    }

    @Test
    void successResponsesAlsoCarryRequestId() throws Exception {
        mvc.perform(get("/health")).andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.notNullValue()));
    }
}
