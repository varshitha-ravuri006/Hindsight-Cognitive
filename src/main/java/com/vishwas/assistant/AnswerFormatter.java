package com.vishwas.assistant;

import com.vishwas.ingest.Fmt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Composes a plain markdown answer straight from tool results (the deterministic path; nothing is invented). */
final class AnswerFormatter {

    private AnswerFormatter() {
    }

    @SuppressWarnings("unchecked")
    static String format(AssistantTools.Result r) {
        if (r.error() != null) {
            return "_" + r.tool().replace('_', ' ') + ": " + r.error() + "_";
        }
        Map<String, Object> d = (Map<String, Object>) r.data();
        return switch (r.tool()) {
            case "search_mismatches" -> {
                List<Map<String, Object>> cases = (List<Map<String, Object>>) d.get("cases");
                if (cases.isEmpty()) {
                    yield "No cases match.";
                }
                StringBuilder s = new StringBuilder("**" + cases.size() + " case" + (cases.size() == 1 ? "" : "s") + "**, "
                        + Fmt.inr(num(d.get("total_exposure_inr"))) + " potential exposure (not a loss):\n\n");
                cases.forEach(c -> s.append("- ").append(line(c)).append('\n'));
                yield s.toString();
            }
            case "repeated_patterns" -> {
                List<Map<String, Object>> vendors = (List<Map<String, Object>>) d.get("vendors");
                if (vendors.isEmpty()) {
                    yield "No vendor had " + d.get("min_cases") + " or more such cases in that period.";
                }
                StringBuilder s = new StringBuilder();
                for (Map<String, Object> v : vendors) {
                    s.append("**").append(v.get("vendor")).append("**: ").append(v.get("cases")).append(" cases\n");
                    ((List<Map<String, Object>>) v.get("details")).forEach(c -> s.append("  - ").append(line(c)).append('\n'));
                }
                yield s.toString();
            }
            case "case_history" -> {
                StringBuilder s = new StringBuilder("**" + d.get("vendor") + "** (GSTIN " + d.get("gstin") + ")\n\n");
                List<Map<String, Object>> cases = (List<Map<String, Object>>) d.get("cases");
                if (cases.isEmpty()) {
                    s.append("No cases in that period.\n");
                }
                cases.forEach(c -> s.append("- ").append(line(c)).append('\n'));
                List<Map<String, Object>> msgs = (List<Map<String, Object>>) d.get("communications");
                if (!msgs.isEmpty()) {
                    s.append("\nLatest vendor messages:\n");
                    msgs.subList(Math.max(0, msgs.size() - 3), msgs.size()).forEach(m -> s.append("- ").append(m.get("date")).append(": ")
                            .append(m.get("summary")).append(m.containsKey("promise_by") ? " (promise by " + m.get("promise_by") + ": "
                                    + String.valueOf(m.get("promise_status")).toLowerCase() + ")" : "").append('\n'));
                }
                yield s.toString();
            }
            case "recall_memory" -> {
                if (Boolean.FALSE.equals(d.get("available"))) {
                    yield "_Memory is off, so nothing was recalled._";
                }
                List<Map<String, Object>> facts = (List<Map<String, Object>>) d.get("facts");
                if (facts.isEmpty()) {
                    yield "Memory holds nothing relevant.";
                }
                StringBuilder s = new StringBuilder("What memory recalls:\n\n");
                facts.stream().limit(6).forEach(f -> s.append("- ").append(f.get("text")).append('\n'));
                yield s.toString();
            }
            case "draft_followup_emails" -> {
                List<Map<String, Object>> drafts = (List<Map<String, Object>>) d.get("drafts");
                if (drafts.isEmpty()) {
                    yield "No vendor needs a follow-up for that.";
                }
                StringBuilder s = new StringBuilder("**" + drafts.size() + " draft" + (drafts.size() == 1 ? "" : "s") + "** (" + d.get("note") + ")\n");
                drafts.forEach(x -> s.append("\n---\n**To:** ").append(x.get("vendor")).append(" <").append(x.get("to")).append(">  \n**Subject:** ")
                        .append(x.get("subject")).append("\n\n").append(x.get("body")).append('\n'));
                yield s.toString();
            }
            default -> "";
        };
    }

    static String line(Map<String, Object> c) {
        StringBuilder s = new StringBuilder().append(c.get("vendor")).append(" · ").append(c.get("invoice")).append(" · ")
                .append(Fmt.month(String.valueOf(c.get("period")))).append(" · ").append(String.valueOf(c.get("type")).replace('_', ' ').toLowerCase())
                .append(" · ").append(Fmt.inr(num(c.get("exposure_inr"))));
        if (c.containsKey("outcome")) {
            s.append(" · **").append(String.valueOf(c.get("outcome")).replace('_', ' ').toLowerCase()).append("**");
            if (c.containsKey("months_late")) {
                s.append(" (").append(c.get("months_late")).append(" month(s) late)");
            }
        } else {
            s.append(" · ").append(String.valueOf(c.get("status")).toLowerCase());
        }
        if (c.containsKey("confirmed_loss_inr")) {
            s.append(" · confirmed loss ").append(Fmt.inr(num(c.get("confirmed_loss_inr"))));
        }
        return s.toString();
    }

    private static BigDecimal num(Object o) {
        return o instanceof BigDecimal b ? b : o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.ZERO;
    }
}
