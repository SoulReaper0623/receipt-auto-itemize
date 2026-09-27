package org.project.service;

import org.project.model.LineItem;
import org.project.model.ParsedReceipt;
import org.project.model.TaxLine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw OCR text into structured receipt fields.
 * Line rules: "KEY: value" headers, "label   12.34" amount lines. An amount line is a
 * tax, the total, a subtotal (ignored), or otherwise a line item. Lines with no amount
 * (e.g. "Trip fare") are ignored rather than guessed at.
 */
@Component
public class ReceiptParser {

    private static final Pattern HEADER = Pattern.compile("^(MERCHANT|DATE|CURRENCY):\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_LINE = Pattern.compile("^(.*?)\\s+(-?\\d+\\.\\d{2})$");
    private static final Pattern TAX = Pattern.compile("^(incl\\.?\\s+)?(VAT|GST|MwSt|Sales Tax)\\s*(\\d+(?:\\.\\d+)?)\\s*%$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL = Pattern.compile("^(grand\\s+)?total$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBTOTAL = Pattern.compile("^sub\\s*total$", Pattern.CASE_INSENSITIVE);

    public ParsedReceipt parse(String rawText) {
        String merchant = null, date = null, currency = null;
        BigDecimal total = null;
        List<TaxLine> taxes = new ArrayList<>();
        List<LineItem> items = new ArrayList<>();

        for (String rawLine : rawText.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                String value = header.group(2).trim();
                switch (header.group(1).toUpperCase()) {
                    case "MERCHANT":
                        merchant = value;
                        break;
                    case "DATE":
                        date = value;
                        break;
                    default:
                        currency = value;
                }
                continue;
            }

            Matcher amountLine = AMOUNT_LINE.matcher(line);
            if (!amountLine.matches()) {
                continue;
            }
            String label = amountLine.group(1).trim();
            BigDecimal amount = new BigDecimal(amountLine.group(2));

            Matcher tax = TAX.matcher(label);
            if (tax.matches()) {
                String name = tax.group(2).toUpperCase();
                BigDecimal percent = new BigDecimal(tax.group(3));
                BigDecimal rate = percent.movePointLeft(2);   // 19 -> 0.19
                boolean inclusive = tax.group(1) != null;      // line started with "incl."
                taxes.add(new TaxLine(name, rate, amount, inclusive));
            } else if (TOTAL.matcher(label).matches()) {
                total = amount;
            } else if (!SUBTOTAL.matcher(label).matches() && !label.isEmpty()) {
                items.add(new LineItem(label, amount));
            }
        }

        return new ParsedReceipt(merchant, date, currency, total, taxes, items);
    }
}
