package org.myorg.dispatchpay.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 CSV: comma-separated values, optionally double-quoted (a quote inside a quoted value is
 * doubled), records ending in CRLF or LF. Quoted values may span lines; record numbers count
 * records, so they match spreadsheet rows.
 */
final class Csv {

    /** Byte-order mark some spreadsheet programs write first; Excel also needs it to read UTF-8. */
    static final char BOM = '﻿';

    /** First characters a spreadsheet would treat as the start of a formula. */
    private static final String FORMULA_START = "=+-@\t\r";

    record Record(int number, List<String> cells) {
    }

    static final class MalformedCsvException extends RuntimeException {

        MalformedCsvException(int record, String problem) {
            super("Row " + record + " " + problem);
        }
    }

    private Csv() {
    }

    static List<Record> parse(String text) {
        List<Record> records = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        int number = 1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    quoted = false;
                    closedQuote = true;
                } else {
                    cell.append(c);
                }
                i++;
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (!cell.isEmpty() || closedQuote) {
                        throw new MalformedCsvException(number,
                                "has a quote inside a value; wrap the value in quotes and double the quote");
                    }
                    quoted = true;
                }
                case ',' -> {
                    cells.add(cell.toString());
                    cell.setLength(0);
                    closedQuote = false;
                }
                case '\r', '\n' -> {
                    cells.add(cell.toString());
                    cell.setLength(0);
                    closedQuote = false;
                    records.add(new Record(number++, List.copyOf(cells)));
                    cells.clear();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                }
                default -> {
                    if (closedQuote) {
                        throw new MalformedCsvException(number, "has text after the closing quote of a value");
                    }
                    cell.append(c);
                }
            }
            i++;
        }
        if (quoted) {
            throw new MalformedCsvException(number, "has a quoted value that is never closed");
        }
        if (!cell.isEmpty() || !cells.isEmpty() || closedQuote) {
            cells.add(cell.toString());
            records.add(new Record(number, List.copyOf(cells)));
        }
        return records;
    }

    /**
     * CSV text for {@code rows}, starting with a byte-order mark, every cell {@link #sanitize
     * sanitized} for spreadsheets.
     */
    static String write(List<List<String>> rows) {
        StringBuilder out = new StringBuilder().append(BOM);
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(sanitize(row.get(i))));
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    /**
     * Neutralizes spreadsheet formula injection: a value starting with {@code = + - @}, tab or
     * carriage return gets a leading apostrophe so it is shown as text, never evaluated.
     */
    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return FORMULA_START.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    private static String quote(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || !value.equals(value.strip());
        return needsQuotes ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
