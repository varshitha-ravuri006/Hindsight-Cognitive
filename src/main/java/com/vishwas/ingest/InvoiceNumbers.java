package com.vishwas.ingest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Invoice-number normalisation and similarity. Vendors and clerks write the same invoice many ways
 * ("INV/26-27/0142", "142", "0142/2026-27"); the normalised form is what exact matching keys on.
 * <ol>
 *   <li>upper-case and trim;</li>
 *   <li>remove financial-year tokens (26-27, 2026-27, 2026-2027, 26/27): only consecutive years;</li>
 *   <li>remove leading alphabetic prefixes (INV, TI, BILL, GST...) with their separators;</li>
 *   <li>remove separators (/ - _ . # : spaces);</li>
 *   <li>strip leading zeros.</li>
 * </ol>
 * Letters that are not a leading prefix are kept, so "142A" and "142B" stay different.
 */
public final class InvoiceNumbers {

    private static final Pattern FY = Pattern.compile("(?<![0-9])(?:20)?([0-9]{2})\\s*[-/]\\s*(?:20)?([0-9]{2})(?![0-9])");
    private static final Pattern PREFIX = Pattern.compile("^[A-Z]+[\\s/\\-_.#:\\\\]*");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s/\\-_.#:\\\\]+");

    private InvoiceNumbers() {
    }

    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().toUpperCase();
        s = removeFinancialYears(s);
        String before;
        do {
            before = s;
            s = PREFIX.matcher(s).replaceFirst("");
        } while (!s.equals(before) && !s.isEmpty() && Character.isLetter(s.charAt(0)));
        s = SEPARATORS.matcher(s).replaceAll("");
        s = s.replaceFirst("^0+(?=.)", "");
        return s.isEmpty() ? raw.trim().toUpperCase().replaceAll("[\\s/\\-_.#:\\\\]+", "") : s;
    }

    private static String removeFinancialYears(String s) {
        Matcher m = FY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            m.appendReplacement(out, (a + 1) % 100 == b ? "/" : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** True when the raw numbers differ although they normalise to the same key (a pure format difference). */
    public static boolean formatDiffers(String a, String b) {
        String ra = a == null ? "" : a.trim().toUpperCase();
        String rb = b == null ? "" : b.trim().toUpperCase();
        return !ra.equals(rb) && normalise(a).equals(normalise(b));
    }

    /** Optimal-string-alignment distance: insertions, deletions, substitutions and adjacent transpositions. */
    public static int editDistance(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    /** Similar enough to be a candidate: short edit distance on normalised forms, never for very short numbers. */
    public static boolean similar(String a, String b, int maxEdits) {
        String na = normalise(a);
        String nb = normalise(b);
        if (na.isEmpty() || nb.isEmpty() || na.equals(nb)) {
            return false;
        }
        if (Math.min(na.length(), nb.length()) < 3) {
            return false;
        }
        return editDistance(na, nb) <= maxEdits;
    }
}
