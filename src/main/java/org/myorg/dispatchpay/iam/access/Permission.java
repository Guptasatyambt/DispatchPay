package org.myorg.dispatchpay.iam.access;

/**
 * Application-defined actions the owner can grant to workers. The owner holds all of them.
 * Managing people, permissions, godown scopes and ownership is owner-only and deliberately not
 * listed here, so it can never be granted. Codes are stored in {@code membership_permission}:
 * rename or remove a constant only with a data migration.
 */
public enum Permission {

    AUDIT_READ,

    GODOWN_MANAGE,
    PRODUCT_READ,
    PRODUCT_MANAGE,
    CUSTOMER_READ,
    CUSTOMER_MANAGE,
    CUSTOMER_RECEIVABLES_READ,
    IMPORT_MANAGE,

    STOCK_READ,
    STOCK_INWARD,
    STOCK_ADJUST,

    ORDER_READ,
    ORDER_CREATE,
    ORDER_EDIT,
    ORDER_CONFIRM,
    ORDER_CANCEL,

    DISPATCH_READ,
    DISPATCH_CREATE,
    DISPATCH_EDIT,
    DISPATCH_CONFIRM,
    DISPATCH_CANCEL,
    DISPATCH_REVERSE,
    RETURN_RECORD,

    INVOICE_READ,
    INVOICE_MANAGE,
    RECEIVABLE_ADJUST,

    PAYMENT_READ,
    PAYMENT_CLAIM_SUBMIT,
    CASH_RECEIPT_RECORD,
    CASH_RECEIPT_REVERSE,
    CHEQUE_MANAGE,
    STATEMENT_IMPORT,
    ALLOCATION_MANAGE,

    VOICE_COMMAND_USE,

    REPORT_READ,
    EXPORT_CREATE,
    REMINDER_MANAGE
}
