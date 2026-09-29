// Formatting helpers: rupees with Indian grouping, dates, labels, and HTML escaping.

const inrFmt = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });
const inrExact = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 0, maximumFractionDigits: 2 });

export function inr(v, exact = false) {
    const n = Number(v || 0);
    return (exact ? inrExact : inrFmt).format(n);
}

export function month(period) {
    if (!period) return "";
    const [y, m] = period.split("-").map(Number);
    return new Date(y, m - 1, 1).toLocaleString("en-IN", { month: "long", year: "numeric" });
}

export function shortMonth(period) {
    if (!period) return "";
    const [y, m] = period.split("-").map(Number);
    return new Date(y, m - 1, 1).toLocaleString("en-IN", { month: "short", year: "numeric" });
}

export function day(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    if (isNaN(d)) return String(iso);
    return d.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric", timeZone: "Asia/Kolkata" });
}

export function dateTime(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    if (isNaN(d)) return String(iso);
    return d.toLocaleString("en-IN", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", timeZone: "Asia/Kolkata" });
}

export function h(s) {
    return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

export const CATEGORY_LABEL = {
    AUTO_RESOLVE: "Auto-resolve",
    RECOMMEND: "Recommend",
    REQUIRE_REVIEW: "Review",
    ESCALATE: "Escalate",
};

export const OUTCOME_LABEL = {
    RESOLVED_LATE: "Appeared late",
    AMENDED: "Amended",
    CREDIT_NOTE: "Credit note",
    CONFIRMED_TYPO: "Confirmed typo",
    DUPLICATE: "Duplicate",
    UNRESOLVED_AT_RISK: "Unresolved, at risk",
};

export const CAUSE_LABEL = {
    TIMING_DIFFERENCE: "Timing difference",
    VENDOR_NOT_FILING: "Vendor not filing",
    DATA_ENTRY_TYPO: "Data-entry or format difference",
    AMENDMENT_EXPECTED: "Vendor amendment expected",
    CREDIT_NOTE_EXPECTED: "Credit note expected",
    DUPLICATE_BOOKING: "Duplicate booking",
    WRONG_GSTIN: "Wrong supplier GSTIN",
    UNKNOWN: "Unknown",
};

export const DIMENSION_LABEL = {
    TIMING: "Filing timing",
    AMOUNT_ACCURACY: "Amount accuracy",
    TAX_HEAD_CORRECTNESS: "Tax head correctness",
    INVOICE_FORMAT: "Invoice details",
    RESPONSIVENESS: "Responsiveness",
    DUPLICATES: "Duplicates",
};

export function badge(category) {
    if (!category) return `<span class="badge pending skeleton">Thinking…</span>`;
    return `<span class="badge ${h(category)}">${h(CATEGORY_LABEL[category] || category)}</span>`;
}

export function confidence(level) {
    if (!level) return "";
    const text = { HIGH: "High", MEDIUM: "Medium", LOW: "Low", NONE: "No history" }[level] || level;
    return `<span class="conf ${h(level)}" title="Confidence from how much history exists and how consistent it is"><i></i><i></i><i></i> ${text}</span>`;
}

/** Markdown from memory (mental models, pages), with raw HTML neutralised. */
export function markdown(text) {
    if (!text) return "";
    if (!window.marked) return `<pre>${h(text)}</pre>`;
    const safe = String(text).replace(/</g, "&lt;");
    return window.marked.parse(safe);
}
