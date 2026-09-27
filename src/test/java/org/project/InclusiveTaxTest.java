package org.project;

import org.junit.jupiter.api.Test;
import org.project.model.ItemizeStatus;
import org.project.model.LineItem;
import org.project.model.ReconciliationResult;
import org.project.model.TaxLine;
import org.project.service.ReceiptParser;
import org.project.service.Reconciler;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code inclusive} flag on a tax:
 * false = tax is added on top of the items ("VAT 19%   2.85"),
 * true  = tax is already inside the item prices ("incl. VAT 19%   3.83").
 * Reconciliation adds only the non-inclusive taxes.
 */
class InclusiveTaxTest {

    private final ReceiptParser parser = new ReceiptParser();
    private final Reconciler reconciler = new Reconciler();

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static List<LineItem> items(String... amounts) {
        LineItem[] result = new LineItem[amounts.length];
        for (int i = 0; i < amounts.length; i++) {
            result[i] = new LineItem("Item " + (i + 1), d(amounts[i]));
        }
        return Arrays.asList(result);
    }

    private static TaxLine onTop(String amount) {
        return new TaxLine("VAT", d("0.19"), d(amount), false);
    }

    private static TaxLine included(String amount) {
        return new TaxLine("VAT", d("0.19"), d(amount), true);
    }

    // ---- Parser: which lines set the flag ----

    @Test
    void plainVatLineIsNotInclusive() {
        List<TaxLine> taxes = parser.parse("VAT 19%      2.85\nTOTAL   17.85").taxes();

        assertThat(taxes).containsExactly(onTop("2.85"));
    }

    @Test
    void inclVatLineIsInclusive() {
        List<TaxLine> taxes = parser.parse("TOTAL   24.00\nincl. VAT 19%   3.83").taxes();

        assertThat(taxes).containsExactly(included("3.83"));
    }

    @Test
    void inclWithoutDotAndAnyCaseIsInclusive() {
        List<TaxLine> taxes = parser.parse("INCL VAT 19%   3.83\nTOTAL 24.00").taxes();

        assertThat(taxes).hasSize(1);
        assertThat(taxes.get(0).isInclusive()).isTrue();
    }

    @Test
    void inclusiveTaxLineIsNotTreatedAsLineItem() {
        assertThat(parser.parse("TOTAL   24.00\nincl. VAT 19%   3.83").lineItems()).isEmpty();
    }

    // ---- Reconciler: inclusive taxes are not added on top ----

    @Test
    void onTopTaxIsAddedToItems() {
        // 15.00 + 2.85 = 17.85
        ReconciliationResult r = reconciler.check(d("17.85"), Collections.singletonList(onTop("2.85")), items("15.00"));

        assertThat(r.getActual()).isEqualByComparingTo("17.85");
        assertThat(r.isReconciled()).isTrue();
    }

    @Test
    void inclusiveTaxIsNotAddedToItems() {
        // Items already contain the 3.83 VAT: 24.00 == 24.00
        ReconciliationResult r = reconciler.check(d("24.00"), Collections.singletonList(included("3.83")), items("24.00"));

        assertThat(r.getActual()).isEqualByComparingTo("24.00");
        assertThat(r.isReconciled()).isTrue();
    }

    @Test
    void addingAnInclusiveTaxWouldDoubleCountIt() {
        // Net-of-tax items (20.17) don't match a gross total when the tax is inclusive.
        ReconciliationResult r = reconciler.check(d("24.00"), Collections.singletonList(included("3.83")), items("20.17"));

        assertThat(r.isReconciled()).isFalse();
        assertThat(r.getDifference()).isEqualByComparingTo("3.83");
    }

    @Test
    void onTopTaxMissingFromItemsDoesNotReconcileWithoutIt() {
        // Same numbers as the clean receipt, but if the VAT were inclusive the items would be 2.85 short.
        ReconciliationResult r = reconciler.check(d("17.85"), Collections.singletonList(included("2.85")), items("15.00"));

        assertThat(r.isReconciled()).isFalse();
        assertThat(r.getDifference()).isEqualByComparingTo("2.85");
    }

    @Test
    void mixedTaxesOnlyAddTheOnTopOnes() {
        // 100.00 items + 7.00 on-top tax = 107.00; the 19.00 inclusive tax is already inside the items.
        List<TaxLine> taxes = Arrays.asList(
                new TaxLine("GST", d("0.07"), d("7.00"), false),
                new TaxLine("VAT", d("0.19"), d("19.00"), true));

        ReconciliationResult r = reconciler.check(d("107.00"), taxes, items("60.00", "40.00"));

        assertThat(r.getActual()).isEqualByComparingTo("107.00");
        assertThat(reconciler.status(d("107.00"), taxes, items("60.00", "40.00"))).isEqualTo(ItemizeStatus.COMPLETE);
    }

    @Test
    void taxiWithSingleGrossItemIsComplete() {
        List<TaxLine> taxes = Collections.singletonList(included("3.83"));

        assertThat(reconciler.status(d("24.00"), taxes, items("24.00"))).isEqualTo(ItemizeStatus.COMPLETE);
    }
}
