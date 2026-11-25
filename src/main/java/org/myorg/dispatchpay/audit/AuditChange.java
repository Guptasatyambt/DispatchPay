package org.myorg.dispatchpay.audit;

/** One field's value before and after a mutation; {@code null} where the field did not exist. */
public record AuditChange(Object before, Object after) {
}
