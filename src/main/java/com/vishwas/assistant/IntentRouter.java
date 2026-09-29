package com.vishwas.assistant;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic routing of a question to tools, used when the model does not call tools (or Groq is down).
 * Pure: the question, today's date and the vendor master in, planned tool calls out.
 */
public final class IntentRouter {

    public record Planned(String tool, Map<String, Object> args) {
    }

    private static final Pattern AMOUNT = Pattern.compile("(?:above|over|more than|greater than|>|at least)\\s*(?:rs\\.?|inr|₹)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(k|lakh|lakhs)?");
    private static final Set<String> STOP = Set.of("sri", "shri", "pvt", "ltd", "llp", "private", "limited", "the", "and", "co");

    private IntentRouter() {
    }

    public static List<Planned> route(String question, LocalDate today, List<String> vendorNames) {
        String q = question.toLowerCase(Locale.ROOT);
        Map<String, Object> window = window(q, today);
        String vendor = vendor(q, vendorNames);
        Map<String, Object> kind = kind(q);
        List<Planned> plan = new ArrayList<>();

        if (has(q, "draft", "e-mail", "email", "write to", "letter to")) {
            Map<String, Object> a = new LinkedHashMap<>();
            if (vendor != null) {
                a.put("vendor", vendor);
            }
            a.put("missing_documents_only", has(q, "missing document", "missing invoice", "missing doc", "missing"));
            plan.add(new Planned("draft_followup_emails", a));
            return plan;
        }
        if (has(q, "repeated", "recurring", "repeat", "again and again", "pattern", "more than once", "multiple times")) {
            Map<String, Object> a = new LinkedHashMap<>(kind);
            a.putAll(window);
            a.put("min_cases", 2);
            plan.add(new Planned("repeated_patterns", a));
            return plan;
        }
        if (vendor != null && has(q, "what happened", "status", "history", "tell me", "update", "where are we", "what about")) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("vendor", vendor);
            if (window.containsKey("period_from") && window.get("period_from").equals(window.get("period_to"))) {
                a.put("period", window.get("period_from"));
            }
            plan.add(new Planned("case_history", a));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("question", question);
            r.put("vendor", vendor);
            if (window.containsKey("from")) {
                r.put("from", window.get("from"));
                r.put("to", window.get("to"));
            }
            plan.add(new Planned("recall_memory", r));
            return plan;
        }
        Matcher amount = AMOUNT.matcher(q.replace("₹ ", "₹"));
        if (amount.find() || has(q, "unresolved", "open ", "outstanding", "pending", "discrepanc", "mismatches", "cases")) {
            Map<String, Object> a = new LinkedHashMap<>(kind);
            a.put("status", has(q, "resolved") && !has(q, "unresolved") ? "RESOLVED" : "UNRESOLVED");
            amount.reset();
            if (amount.find()) {
                double v = Double.parseDouble(amount.group(1).replace(",", ""));
                String unit = amount.group(2);
                if ("k".equals(unit)) {
                    v *= 1_000;
                } else if (unit != null && unit.startsWith("lakh")) {
                    v *= 1_00_000;
                }
                a.put("min_exposure_inr", v);
            }
            if (vendor != null) {
                a.put("vendor", vendor);
            }
            a.putAll(window.entrySet().stream().filter(e -> e.getKey().startsWith("period"))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
            plan.add(new Planned("search_mismatches", a));
            return plan;
        }
        if (vendor != null) {
            plan.add(new Planned("case_history", new LinkedHashMap<>(Map.of("vendor", vendor))));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("question", question);
        if (vendor != null) {
            r.put("vendor", vendor);
        }
        plan.add(new Planned("recall_memory", r));
        return plan;
    }

    /** "last month", "this month", "this quarter", "last quarter" as period and date ranges (quarters of the year). */
    static Map<String, Object> window(String q, LocalDate today) {
        Map<String, Object> w = new LinkedHashMap<>();
        YearMonth now = YearMonth.from(today);
        YearMonth from = null;
        YearMonth to = null;
        if (q.contains("last month") || q.contains("previous month")) {
            from = to = now.minusMonths(1);
        } else if (q.contains("this month")) {
            from = to = now;
        } else if (q.contains("this quarter")) {
            from = now.minusMonths((now.getMonthValue() - 1) % 3);
            to = now;
        } else if (q.contains("last quarter") || q.contains("previous quarter")) {
            YearMonth start = now.minusMonths((now.getMonthValue() - 1) % 3);
            from = start.minusMonths(3);
            to = start.minusMonths(1);
        }
        if (from != null) {
            w.put("period_from", from.toString());
            w.put("period_to", to.toString());
            w.put("from", from.atDay(1).toString());
            w.put("to", to.atEndOfMonth().toString());
        }
        return w;
    }

    static Map<String, Object> kind(String q) {
        Map<String, Object> k = new LinkedHashMap<>();
        if (has(q, "invoice-number", "invoice number", "invoice no", "number format", "format")) {
            k.put("type", "INVOICE_NO_FORMAT");
        } else if (has(q, "duplicate")) {
            k.put("type", "POSSIBLE_DUPLICATE");
        } else if (has(q, "tax head", "igst", "cgst")) {
            k.put("type", "TAX_HEAD_MISMATCH");
        } else if (has(q, "gstin")) {
            k.put("type", "GSTIN_MISMATCH");
        } else if (has(q, "amount")) {
            k.put("type", "AMOUNT_MISMATCH");
        } else if (has(q, "late", "timing", "missing in 2b", "not filed", "missing from gstr-2b")) {
            k.put("dimension", "TIMING");
        }
        return k;
    }

    /**
     * The vendor a question is about: its first distinctive name word must appear; when that word is shared
     * (Sri Balaji Traders / Sri Balaji Enterprises) the next word must appear too, or no vendor is chosen.
     */
    static String vendor(String q, List<String> names) {
        List<String> hits = new ArrayList<>();
        for (String name : names) {
            List<String> tokens = tokens(name);
            if (tokens.isEmpty() || !q.contains(tokens.get(0))) {
                continue;
            }
            long sharing = names.stream().filter(n -> !n.equals(name) && tokens(n).stream().findFirst().orElse("").equals(tokens.get(0))).count();
            if (sharing == 0 || (tokens.size() > 1 && q.contains(tokens.get(1)))) {
                hits.add(name);
            }
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static List<String> tokens(String name) {
        return java.util.Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2 && !STOP.contains(t)).toList();
    }

    private static boolean has(String q, String... words) {
        for (String w : words) {
            if (q.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
