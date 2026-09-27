package org.project.error;

import org.project.model.ReconciliationResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** Thrown when a user's item edit no longer adds up to the transaction total (409). */
public class ReconciliationException extends ApiException {

    public ReconciliationException(ReconciliationResult result) {
        super(ErrorCode.ITEMS_DO_NOT_RECONCILE, ErrorCode.ITEMS_DO_NOT_RECONCILE.defaultMessage(), details(result));
    }

    private static Map<String, Object> details(ReconciliationResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("expected", result.getExpected());
        details.put("actual", result.getActual());
        details.put("difference", result.getDifference());
        return details;
    }
}
