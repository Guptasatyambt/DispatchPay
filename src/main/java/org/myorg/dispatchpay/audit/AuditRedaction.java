package org.myorg.dispatchpay.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Replaces values of secret-bearing fields, both when history is written and when it is read. */
final class AuditRedaction {

    static final String REDACTED = "[REDACTED]";

    private static final List<String> SENSITIVE = List.of("token", "secret", "password", "pepper", "otp", "hash");

    private AuditRedaction() {
    }

    static boolean isSensitive(String field) {
        String lower = field.toLowerCase(Locale.ROOT);
        return SENSITIVE.stream().anyMatch(lower::contains);
    }

    static Map<String, AuditChange> redact(Map<String, AuditChange> changes) {
        Map<String, AuditChange> safe = new LinkedHashMap<>();
        changes.forEach((field, change) -> safe.put(field, isSensitive(field)
                ? new AuditChange(change.before() == null ? null : REDACTED, change.after() == null ? null : REDACTED)
                : change));
        return safe;
    }
}
