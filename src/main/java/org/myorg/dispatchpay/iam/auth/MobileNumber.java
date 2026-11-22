package org.myorg.dispatchpay.iam.auth;

import java.util.regex.Pattern;

import org.myorg.dispatchpay.platform.error.ApiException;
import org.myorg.dispatchpay.platform.error.ErrorCode;

/** Indian mobile number normalized to E.164 ({@code +91XXXXXXXXXX}). */
public record MobileNumber(String e164) {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s()-]");
    private static final Pattern NATIONAL = Pattern.compile("[6-9]\\d{9}");

    /**
     * Accepts {@code 9876543210}, {@code 09876543210}, {@code 919876543210} and
     * {@code +91 98765-43210}; anything else is a validation error.
     */
    public static MobileNumber parse(String raw) {
        if (raw == null) {
            throw invalid();
        }
        String digits = SEPARATORS.matcher(raw.trim()).replaceAll("");
        if (digits.startsWith("+91")) {
            digits = digits.substring(3);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (!NATIONAL.matcher(digits).matches()) {
            throw invalid();
        }
        return new MobileNumber("+91" + digits);
    }

    /** Safe for logs: {@code +91******3210}. */
    public String masked() {
        return e164.substring(0, 3) + "******" + e164.substring(e164.length() - 4);
    }

    @Override
    public String toString() {
        return masked();
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "mobile must be a valid Indian mobile number");
    }
}
