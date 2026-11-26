package org.myorg.dispatchpay.imports;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.myorg.dispatchpay.platform.search.SearchText;

/**
 * Reads typed values from one row's cells and collects its errors, in the order the cells are read,
 * so the same row always reports the same errors. A reader returns {@code null} for a blank cell and
 * for an invalid one (after recording the error).
 */
final class RowReader {

    /** Separates list values (aliases, conversions) inside one cell. */
    static final String LIST_SEPARATOR = "|";

    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]+)?");

    private final Map<String, String> values;
    private final List<RowError> errors = new ArrayList<>();

    RowReader(Map<String, String> values) {
        this.values = values;
    }

    List<RowError> errors() {
        return errors;
    }

    boolean valid() {
        return errors.isEmpty();
    }

    void error(String column, String code, String message) {
        errors.add(new RowError(column, code, message));
    }

    /** The trimmed cell, or {@code null} when it is blank or the column is absent. */
    String text(String column) {
        String value = values.get(column);
        return value == null || value.isBlank() ? null : value.strip();
    }

    String required(String column, int maxLength) {
        String value = text(column);
        if (value == null) {
            error(column, RowError.REQUIRED, column + " is required");
            return null;
        }
        return withinLength(column, value, maxLength);
    }

    String optional(String column, int maxLength) {
        String value = text(column);
        return value == null ? null : withinLength(column, value, maxLength);
    }

    /** Digits only: no sign, decimals or separators. */
    Long wholeNumber(String column, long min, long max) {
        String value = text(column);
        if (value == null) {
            return null;
        }
        if (!WHOLE_NUMBER.matcher(value).matches()) {
            error(column, RowError.INVALID_NUMBER, column + " must be a whole number");
            return null;
        }
        // 18 digits always fit in a long; longer values are out of every range used here.
        long number = value.length() > 18 ? Long.MAX_VALUE : Long.parseLong(value);
        if (value.length() > 18 || number < min || number > max) {
            error(column, RowError.OUT_OF_RANGE, column + " must be between " + min + " and " + max);
            return null;
        }
        return number;
    }

    /** Digits with an optional decimal part, such as {@code 12} or {@code 0.5}; no sign or exponent. */
    BigDecimal decimal(String column, String value) {
        if (!DECIMAL.matcher(value).matches()) {
            error(column, RowError.INVALID_NUMBER, column + " must be a number such as 12 or 0.5");
            return null;
        }
        return new BigDecimal(value);
    }

    /**
     * The {@value #LIST_SEPARATOR}-separated values of a cell, trimmed, empty ones skipped. Values
     * that are equal ignoring case and spacing are reported as duplicates.
     */
    List<String> aliases(String column, int maxLength) {
        String value = text(column);
        if (value == null) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : split(value)) {
            String alias = withinLength(column, part, maxLength);
            if (alias == null) {
                continue;
            }
            if (!seen.add(SearchText.normalize(alias))) {
                error(column, RowError.DUPLICATE_ALIAS, "Alias \"" + alias + "\" is listed more than once");
                continue;
            }
            aliases.add(alias);
        }
        return aliases;
    }

    static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split(Pattern.quote(LIST_SEPARATOR))) {
            if (!part.isBlank()) {
                parts.add(part.strip());
            }
        }
        return parts;
    }

    private String withinLength(String column, String value, int maxLength) {
        if (value.length() > maxLength) {
            error(column, RowError.TOO_LONG, column + " must be at most " + maxLength + " characters");
            return null;
        }
        return value;
    }
}
