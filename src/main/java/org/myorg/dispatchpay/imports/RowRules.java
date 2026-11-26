package org.myorg.dispatchpay.imports;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.myorg.dispatchpay.iam.access.BusinessAccess;

/**
 * Row checks and creation for one {@link ImportType}. The same checks run at validation and again
 * at commit, against the data current at that moment and the access of the member acting, so a
 * commit never creates what validation would now reject.
 */
interface RowRules {

    /** One staged row: its number in the file and its raw cells by column. */
    record Row(int number, Map<String, String> values) {
    }

    ImportType type();

    /** The errors of each row, in the order given; an empty list for a valid row. Changes nothing. */
    List<List<RowError>> validate(BusinessAccess access, List<Row> rows);

    /**
     * Creates one record per row, all valid, attributed to {@code access}'s member as initiator.
     * Runs in the commit transaction; returns the created ids in row order.
     */
    List<UUID> commit(BusinessAccess access, List<Row> rows);
}
