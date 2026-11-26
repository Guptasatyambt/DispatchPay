package org.myorg.dispatchpay.imports;

/**
 * Lifecycle of an import:
 * {@code AWAITING_UPLOAD → VALIDATING → VALIDATED | INVALID}, then
 * {@code VALIDATED → COMMITTING → COMMITTED | FAILED}. {@code INVALID}, {@code COMMITTED} and
 * {@code FAILED} are final: to retry, fix the file and start a new import.
 */
public enum ImportStatus {

    /** Created; the client uploads the file to the pre-signed URL. */
    AWAITING_UPLOAD,
    /** Waiting for or being parsed and checked by a worker; nothing is changed. */
    VALIDATING,
    /** Every row is valid; the import may be committed. */
    VALIDATED,
    /** The file or some rows are invalid; see the failure and the row errors. */
    INVALID,
    /** Waiting for or being committed by a worker. */
    COMMITTING,
    /** Every row was created, in one transaction. */
    COMMITTED,
    /** Nothing was created: rows conflicted with data changed since validation, or access was lost. */
    FAILED
}
