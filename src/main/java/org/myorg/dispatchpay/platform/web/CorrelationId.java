package org.myorg.dispatchpay.platform.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/** Correlation ID carried on every request, log line, error body and background event. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private CorrelationId() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    static String generate() {
        return UUID.randomUUID().toString();
    }
}
