package com.vishwas.ingest;

import java.util.regex.Pattern;

/**
 * GSTIN format and check-digit validation. A GSTIN is 15 characters: 2-digit state code, 10-character PAN,
 * entity number, the letter Z, and a mod-36 check character.
 */
public final class Gstin {

    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern FORMAT = Pattern.compile("^[0-3][0-9][A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

    private Gstin() {
    }

    public static String clean(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    public static boolean hasValidFormat(String gstin) {
        return gstin != null && FORMAT.matcher(gstin).matches();
    }

    public static boolean isValid(String gstin) {
        return hasValidFormat(gstin) && checkChar(gstin.substring(0, 14)) == gstin.charAt(14);
    }

    /** The mod-36 check character for the first 14 characters. */
    public static char checkChar(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int product = CHARS.indexOf(first14.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }

    public static String stateCode(String gstin) {
        return gstin == null || gstin.length() < 2 ? "" : gstin.substring(0, 2);
    }
}
