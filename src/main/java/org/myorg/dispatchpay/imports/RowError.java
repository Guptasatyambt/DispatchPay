package org.myorg.dispatchpay.imports;

/**
 * Why one cell (or the whole row, when {@code column} is {@code null}) cannot be imported. Codes are
 * stable; clients branch on them, never on the message.
 */
public record RowError(String column, String code, String message) {

    public static final String REQUIRED = "REQUIRED";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String INVALID_FORMAT = "INVALID_FORMAT";
    public static final String INVALID_NUMBER = "INVALID_NUMBER";
    public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
    public static final String INVALID_UNIT = "INVALID_UNIT";
    public static final String INVALID_CONVERSION = "INVALID_CONVERSION";
    public static final String DUPLICATE_ALIAS = "DUPLICATE_ALIAS";
    public static final String DUPLICATE_IN_FILE = "DUPLICATE_IN_FILE";
    public static final String ALREADY_EXISTS = "ALREADY_EXISTS";
    public static final String PERMISSION_REQUIRED = "PERMISSION_REQUIRED";
    public static final String UNKNOWN_PRODUCT = "UNKNOWN_PRODUCT";
    public static final String PRODUCT_INACTIVE = "PRODUCT_INACTIVE";
    public static final String UNKNOWN_GODOWN = "UNKNOWN_GODOWN";
    public static final String GODOWN_NOT_IN_SCOPE = "GODOWN_NOT_IN_SCOPE";
    public static final String UNIT_NOT_CONVERTIBLE = "UNIT_NOT_CONVERTIBLE";
    public static final String COLUMN_COUNT = "COLUMN_COUNT";
}
