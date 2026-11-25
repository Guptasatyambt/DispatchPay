package org.myorg.dispatchpay.platform.web;

import java.util.Set;

import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;
import tools.jackson.databind.JsonNode;

/**
 * Reads a PATCH body where an absent field is left unchanged and an explicit {@code null} clears an
 * optional field. Unknown fields are rejected so a typo never silently changes nothing.
 */
public final class PatchRequest {

    /** A requested change to an optional field; a {@code null} value clears it. */
    public record Change<T>(T value) {
    }

    private final JsonNode body;

    private PatchRequest(JsonNode body) {
        this.body = body;
    }

    public static PatchRequest of(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject()) {
            throw invalid("The request body must be a JSON object");
        }
        for (String name : body.propertyNames()) {
            if (!fields.contains(name)) {
                throw invalid("Unknown field: " + name);
            }
        }
        return new PatchRequest(body);
    }

    /** A field that cannot be cleared: {@code null} when absent; an explicit null or blank value is invalid. */
    public String text(String field, int maxLength) {
        if (!body.has(field)) {
            return null;
        }
        String value = stringValue(field, maxLength);
        if (value == null) {
            throw invalid(field + " must not be blank");
        }
        return value;
    }

    /** An optional text field: {@code null} when absent; null or blank clears it. */
    public Change<String> optionalText(String field, int maxLength) {
        if (!body.has(field)) {
            return null;
        }
        return new Change<>(stringValue(field, maxLength));
    }

    /** An optional whole number in [{@code min}, {@code max}]: {@code null} when absent; null clears it. */
    public Change<Long> optionalWholeNumber(String field, long min, long max) {
        if (!body.has(field)) {
            return null;
        }
        return new Change<>(body.get(field).isNull() ? null : wholeNumber(field, min, max));
    }

    /** A whole number in [{@code min}, {@code max}] that must be present. */
    public long requiredWholeNumber(String field, long min, long max) {
        if (!body.has(field) || body.get(field).isNull()) {
            throw invalid(field + " is required");
        }
        return wholeNumber(field, min, max);
    }

    private String stringValue(String field, int maxLength) {
        JsonNode node = body.get(field);
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw invalid(field + " must be a string");
        }
        String value = node.asString().strip();
        if (value.length() > maxLength) {
            throw invalid(field + " must be at most " + maxLength + " characters");
        }
        return value.isEmpty() ? null : value;
    }

    private long wholeNumber(String field, long min, long max) {
        JsonNode node = body.get(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid(field + " must be a whole number");
        }
        long value = node.longValue();
        if (value < min || value > max) {
            throw invalid(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
