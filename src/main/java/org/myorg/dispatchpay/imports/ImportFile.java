package org.myorg.dispatchpay.imports;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an uploaded file into staged rows, or rejects the whole file. Row contents are checked
 * later by the type's {@link RowRules}; here only the file's shape is checked.
 */
final class ImportFile {

    static final String FILE_MISSING = "FILE_MISSING";
    static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    static final String FILE_NOT_CSV = "FILE_NOT_CSV";
    static final String FILE_ENCODING = "FILE_ENCODING";
    static final String HEADER_INVALID = "HEADER_INVALID";
    static final String NO_ROWS = "NO_ROWS";
    static final String TOO_MANY_ROWS = "TOO_MANY_ROWS";

    /** The whole file cannot be imported. */
    static final class Rejected extends RuntimeException {

        private final String code;

        Rejected(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /**
     * One data record. {@code values} holds the cells by column; a record with the wrong number of
     * cells carries a {@link RowError#COLUMN_COUNT} error.
     */
    record ParsedRow(int rowNumber, Map<String, String> values, RowError shapeError) {
    }

    /** @param columns the file's columns in file order, by their documented names */
    record Parsed(List<String> columns, List<ParsedRow> rows) {
    }

    private ImportFile() {
    }

    static Parsed parse(byte[] content, ImportType type, int maxRows) {
        String text = decode(content);
        if (text.indexOf('\0') >= 0) {
            throw new Rejected(FILE_NOT_CSV, "The file is not a CSV text file");
        }
        List<Csv.Record> records;
        try {
            records = Csv.parse(text);
        } catch (Csv.MalformedCsvException e) {
            throw new Rejected(FILE_NOT_CSV, e.getMessage());
        }
        if (records.isEmpty() || isBlank(records.getFirst())) {
            throw new Rejected(HEADER_INVALID, "The first row must name the columns: " + type.columns());
        }
        List<String> columns = header(records.getFirst(), type);

        List<ParsedRow> rows = new ArrayList<>();
        for (Csv.Record record : records.subList(1, records.size())) {
            if (isBlank(record)) {
                continue;
            }
            if (rows.size() == maxRows) {
                throw new Rejected(TOO_MANY_ROWS, "The file has more than " + maxRows + " rows");
            }
            rows.add(row(record, columns));
        }
        if (rows.isEmpty()) {
            throw new Rejected(NO_ROWS, "The file has no rows below the header");
        }
        return new Parsed(columns, rows);
    }

    /** Strict UTF-8, without a leading byte-order mark. */
    private static String decode(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
            return !text.isEmpty() && text.charAt(0) == Csv.BOM ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new Rejected(FILE_ENCODING, "The file must be UTF-8 text; save it as \"CSV UTF-8\"");
        }
    }

    /** Column names are matched ignoring case and surrounding spaces, in any order. */
    private static List<String> header(Csv.Record record, ImportType type) {
        Map<String, String> known = new HashMap<>();
        type.columns().forEach(column -> known.put(column.toLowerCase(Locale.ROOT), column));
        List<String> columns = new ArrayList<>();
        for (String cell : record.cells()) {
            String column = known.get(cell.strip().toLowerCase(Locale.ROOT));
            if (column == null) {
                throw new Rejected(HEADER_INVALID,
                        "Unknown column \"" + cell.strip() + "\"; the columns are " + type.columns());
            }
            if (columns.contains(column)) {
                throw new Rejected(HEADER_INVALID, "Column \"" + column + "\" appears more than once");
            }
            columns.add(column);
        }
        List<String> missing = type.columns().stream()
                .filter(column -> type.requiredColumns().contains(column) && !columns.contains(column))
                .toList();
        if (!missing.isEmpty()) {
            throw new Rejected(HEADER_INVALID, "Missing required column(s): " + missing);
        }
        return List.copyOf(columns);
    }

    private static ParsedRow row(Csv.Record record, List<String> columns) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            values.put(columns.get(i), i < record.cells().size() ? record.cells().get(i) : "");
        }
        RowError shapeError = record.cells().size() == columns.size() ? null
                : new RowError(null, RowError.COLUMN_COUNT, "The row has " + record.cells().size()
                        + " values but the header has " + columns.size() + " columns");
        return new ParsedRow(record.number(), values, shapeError);
    }

    private static boolean isBlank(Csv.Record record) {
        return record.cells().stream().allMatch(String::isBlank);
    }
}
