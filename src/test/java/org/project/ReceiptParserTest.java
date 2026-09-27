package org.project;

import org.junit.jupiter.api.Test;
import org.project.model.ItemizeStatus;
import org.project.model.LineItem;
import org.project.model.ParsedReceipt;
import org.project.model.ReconciliationResult;
import org.project.model.TaxLine;
import org.project.service.ReceiptParser;
import org.project.service.Reconciler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks extraction against task-a/fixtures/task-a/gold.json. */
class ReceiptParserTest {

    private final ReceiptParser parser = new ReceiptParser();
    private final Reconciler reconciler = new Reconciler();

    private ParsedReceipt parse(String fixture) throws IOException {
        return parser.parse(new String(Files.readAllBytes(Paths.get("task-a/fixtures/task-a", fixture + ".txt")), StandardCharsets.UTF_8));
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void clean() throws IOException {
        ParsedReceipt r = parse("receipt-clean");

        assertThat(r.merchant()).isEqualTo("Cafe Mitte");
        assertThat(r.date()).isEqualTo("2026-03-12");
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.grandTotal()).isEqualByComparingTo("17.85");
        assertThat(r.taxes()).containsExactly(new TaxLine("VAT", d("0.19"), d("2.85"), false));
        assertThat(r.lineItems()).containsExactly(
                new LineItem("Espresso", d("3.50")),
                new LineItem("Sandwich", d("8.90")),
                new LineItem("Mineral water", d("2.60")));
        assertThat(reconciler.status(r.grandTotal(), r.taxes(), r.lineItems())).isEqualTo(ItemizeStatus.COMPLETE);
    }

    @Test
    void taxOnly() throws IOException {
        ParsedReceipt r = parse("receipt-tax-only");

        assertThat(r.merchant()).isEqualTo("Berlin Taxi GmbH");
        assertThat(r.grandTotal()).isEqualByComparingTo("24.00");
        assertThat(r.taxes()).containsExactly(new TaxLine("VAT", d("0.19"), d("3.83"), true));
        assertThat(r.lineItems()).isEmpty();
        assertThat(reconciler.status(r.grandTotal(), r.taxes(), r.lineItems())).isEqualTo(ItemizeStatus.NEEDS_REVIEW);
    }

    @Test
    void mismatchKeepsItemsAndTotalAsIs() throws IOException {
        ParsedReceipt r = parse("receipt-mismatch");

        assertThat(r.merchant()).isEqualTo("Hotel Shop");
        assertThat(r.date()).isEqualTo("2026-03-13");
        assertThat(r.grandTotal()).isEqualByComparingTo("18.50");
        assertThat(r.taxes()).containsExactly(new TaxLine("VAT", d("0.19"), d("1.90"), false));
        assertThat(r.lineItems()).containsExactly(
                new LineItem("Water", d("4.00")),
                new LineItem("Snacks", d("6.00")));
        assertThat(reconciler.status(r.grandTotal(), r.taxes(), r.lineItems())).isEqualTo(ItemizeStatus.NEEDS_REVIEW);
    }

    @Test
    void noTotalFails() {
        ParsedReceipt r = parser.parse("just some text");

        assertThat(reconciler.status(r.grandTotal(), r.taxes(), r.lineItems())).isEqualTo(ItemizeStatus.FAILED);
    }

    @Test
    void reconcileReportsDifference() {
        ReconciliationResult result = reconciler.check(d("18.50"),
                Arrays.asList(new TaxLine("VAT", d("0.19"), d("1.90"), false)),
                Arrays.asList(new LineItem("Water", d("4.00")), new LineItem("Snacks", d("6.00"))));

        assertThat(result.isReconciled()).isFalse();
        assertThat(result.getExpected()).isEqualByComparingTo("18.50");
        assertThat(result.getActual()).isEqualByComparingTo("11.90");
        assertThat(result.getDifference()).isEqualByComparingTo("6.60");
    }
}
