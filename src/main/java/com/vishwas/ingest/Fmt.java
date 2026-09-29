package com.vishwas.ingest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** Human formatting shared by memory text, recommendations and the API: Indian digit grouping, month labels. */
public final class Fmt {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private Fmt() {
    }

    /** "Rs 1,42,000" or "Rs 15,899.99" (Indian lakh grouping; paise only when present). */
    public static String inr(BigDecimal amount) {
        if (amount == null) {
            return "Rs 0";
        }
        BigDecimal v = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = v.signum() < 0;
        String plain = v.abs().toPlainString();
        String rupees = plain.substring(0, plain.indexOf('.'));
        String paise = plain.substring(plain.indexOf('.') + 1);
        StringBuilder grouped = new StringBuilder();
        int n = rupees.length();
        if (n <= 3) {
            grouped.append(rupees);
        } else {
            String head = rupees.substring(0, n - 3);
            String tail = rupees.substring(n - 3);
            StringBuilder h = new StringBuilder();
            for (int i = head.length(); i > 0; i -= 2) {
                h.insert(0, head.substring(Math.max(0, i - 2), i));
                if (i - 2 > 0) {
                    h.insert(0, ',');
                }
            }
            grouped.append(h).append(',').append(tail);
        }
        return (negative ? "-" : "") + "Rs " + grouped + ("00".equals(paise) ? "" : "." + paise);
    }

    /** "May 2026". */
    public static String month(String period) {
        YearMonth ym = YearMonth.parse(period);
        return ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
    }

    /** "12 May 2026". */
    public static String day(LocalDate date) {
        return date == null ? "unknown date" : date.format(DAY);
    }

    public static String day(Instant instant) {
        return instant == null ? "unknown date" : instant.atZone(IST).toLocalDate().format(DAY);
    }

    public static String period(Instant instant) {
        return YearMonth.from(instant.atZone(IST)).toString();
    }
}
