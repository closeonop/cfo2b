package com.empmg.platform.common;

import java.util.regex.Pattern;

/** Password policy mirrored in the frontend with Zod. */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    private static final Pattern HAS_LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*[0-9].*");

    public static final String RULE_TEXT = "At least 10 characters, with at least one letter and one number.";

    /** Returns an error message, or null if the password is acceptable. */
    public static String validate(String password, String email) {
        if (password == null || password.length() < 10) {
            return "Password must be at least 10 characters long.";
        }
        if (!HAS_LETTER.matcher(password).matches() || !HAS_DIGIT.matcher(password).matches()) {
            return "Password must contain at least one letter and one number.";
        }
        if (email != null && email.equalsIgnoreCase(password)) {
            return "Password must be different from your email address.";
        }
        return null;
    }
}
