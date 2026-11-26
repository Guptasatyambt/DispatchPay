package org.myorg.dispatchpay.customer;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Field rules for customers, shared by the API and imports so both accept exactly the same values.
 */
public final class CustomerRules {

    public static final int NAME_MAX = 200;
    public static final int CONTACT_MAX = 120;
    public static final int PHONE_MAX = 20;
    public static final int ADDRESS_MAX = 500;
    public static final int ALIAS_MAX = 120;
    public static final int TERMS_MAX = 365;
    /** Upper bound for a credit limit in paise (₹1,000 crore). */
    public static final long CREDIT_LIMIT_MAX = 1_000_000_000_000L;
    public static final String PHONE = "\\+?[0-9][0-9 -]{5,19}";
    public static final String PHONE_MESSAGE = "phone must be 6-20 digits, optionally with a leading +, spaces or -";
    public static final Pattern PHONE_PATTERN = Pattern.compile(PHONE);
    public static final String USERNAME = "[A-Za-z0-9][A-Za-z0-9._-]{2,39}";
    public static final String USERNAME_MESSAGE =
            "username must be 3-40 letters, digits, '.', '_' or '-', starting with a letter or digit";
    public static final Pattern USERNAME_PATTERN = Pattern.compile(USERNAME);

    private CustomerRules() {
    }

    /** The stored form of a valid username (lower-case), or {@code null} when {@code raw} is not valid. */
    public static String username(String raw) {
        String username = raw.strip();
        return USERNAME_PATTERN.matcher(username).matches() ? username.toLowerCase(Locale.ROOT) : null;
    }
}
