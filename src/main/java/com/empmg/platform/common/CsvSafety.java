package com.empmg.platform.common;

/**
 * CSV formula-injection protection. Text cells that could be interpreted as
 * formulas by spreadsheet apps get a leading apostrophe on export.
 * Never apply to numeric cells (negative numbers start with "-").
 */
public final class CsvSafety {

    private CsvSafety() {}

    public static String safe(String value) {
        if (value == null || value.isEmpty()) return value;
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '@' || first == '\t' || first == '\r'
                || (first == '-' && value.length() > 1 && !(value.charAt(1) >= '0' && value.charAt(1) <= '9'))) {
            return "'" + value;
        }
        return value;
    }
}
