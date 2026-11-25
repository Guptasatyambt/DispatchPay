package org.myorg.dispatchpay.platform.search;

import java.util.Locale;
import java.util.regex.Pattern;

/** Text normalization for case- and spacing-insensitive search and alias matching. */
public final class SearchText {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SearchText() {
    }

    /** Lower-cased with runs of whitespace collapsed, so "Tata  SALT" and "tata salt" are equal. */
    public static String normalize(String text) {
        return WHITESPACE.matcher(text.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /**
     * A SQL {@code LIKE} pattern matching values that contain {@code normalized}, for use with
     * {@code ESCAPE '\'}: wildcards in the query are matched literally.
     */
    public static String containsPattern(String normalized) {
        String escaped = normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
