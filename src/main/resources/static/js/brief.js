// The investigation brief: original vs normalised values, ranked hypotheses with evidence, the exact past
// cases behind them, confidence (kept separate from history), the next step and Approve / Modify / Reject.
import { api } from "./api.js";
import { badge, CAUSE_LABEL, confidence, day, h, inr, month, OUTCOME_LABEL, shortMonth } from "./fmt.js";
import { banner, closePanel, openPanel, panelBody, skeletonLines } from "./ui.js";

const REASONS = [
    ["TIMING_DIFFERENCE", "Timing difference"],
    ["TYPO", "Typo"],
    ["DUPLICATE", "Duplicate"],
    ["MISSING_DOCUMENT", "Missing document"],
    ["INCORRECT_AMOUNT", "Incorrect amount"],
    ["OTHER", "Other"],
];

export async function openBrief(id, period) {
    openPanel(`<h2 class="skeleton">Loading the brief</h2>${skeletonLines(6)}`);
    try {
        const b = await api(`/api/cases/${id}?period=${encodeURIComponent(period || "")}`);
        panelBody().innerHTML = render(b);
        wire(b, period);
    } catch (e) {
        panelBody().innerHTML = `<div class="banner">${h(e.message)}</div>`;
    }
}

function render(b) {
    const r = b.row;
    return `
    <p class="muted small">${h(r.vendor)} · <span class="mono">${h(r.vendorGstin)}</span></p>
    <h2>${h(r.invoice)} <span class="muted">· ${h(r.typeLabel)} · ${h(r.periodLabel)}</span></h2>
    <p style="margin:6px 0 0; display:flex; gap:10px; align-items:center; flex-wrap:wrap">
        ${badge(r.category)} <strong>${inr(r.exposure)}</strong> <span class="muted">potential exposure (not a loss)</span>
        ${r.source === "TEXTBOOK" ? `<span class="tag">textbook action: memory was not available</span>` : ""}
        <button class="secondary" id="brief-vendor" style="margin-left:auto">Vendor profile</button>
    </p>
    ${r.headline ? `<p class="lead" style="margin-top:10px">${h(r.headline)}</p>` : ""}
    ${b.verdictNote ? `<div class="banner info">Outcome so far: ${h(b.verdictNote)}</div>` : ""}

    ${valuesSection(b)}
    ${b.candidates.length ? candidatesSection(b) : ""}
    ${hypothesesSection(b)}
    ${pastCasesSection(b)}
    ${confidenceSection(b)}
    ${memorySection(b)}
    ${communicationsSection(b)}
    ${trackRecordSection(b)}
    ${decisionSection(b)}
    <p class="disclaimer">Informational only, not tax advice.</p>`;
}

function valuesSection(b) {
    const x = b.books || {};
    const y = b.gstr2b || {};
    const rows = [
        ["Invoice number (as written)", x.invoiceNo, y.invoiceNo, true],
        ["Invoice number (normalised)", x.normalisedNo, y.normalisedNo, false],
        ["Supplier GSTIN", x.gstin, y.gstin, true],
        ["Invoice date", day(x.invoiceDate), day(y.invoiceDate), true],
        ["Taxable value", money(x.taxable), money(y.taxable), true],
        ["IGST", money(x.igst), money(y.igst), true],
        ["CGST", money(x.cgst), money(y.cgst), true],
        ["SGST", money(x.sgst), money(y.sgst), true],
        ["Total ITC", money(x.itc), money(y.itc), true],
    ];
    const extra = [];
    if (x.voucherNo) extra.push(`Booked as ${h(x.voucherNo)} on ${day(x.bookingDate)}`);
    if (y.filedOn) extra.push(`Supplier filed GSTR-1 for ${h(month(y.filingPeriod))} on ${day(y.filedOn)}`);
    return `<section><h3>Books vs GSTR-2B</h3>
        <table class="grid"><thead><tr><th>Field</th><th>Purchase register</th><th>GSTR-2B</th></tr></thead><tbody>
        ${rows.map(([label, a, c, compare]) => {
            const differs = compare && b.books && b.gstr2b && String(a ?? "") !== String(c ?? "");
            return `<tr class="${differs ? "diff" : ""}"><td>${label}</td><td class="mono">${h(a ?? "")}</td><td class="mono">${h(c ?? "")}</td></tr>`;
        }).join("")}
        </tbody></table>
        ${!b.books ? `<p class="note">Not in the purchase register.</p>` : ""}${!b.gstr2b ? `<p class="note">Not in GSTR-2B.</p>` : ""}
        ${extra.length ? `<p class="note">${extra.join(" · ")}</p>` : ""}
        ${b.note ? `<p class="note">${h(b.note)}</p>` : ""}
    </section>`;
}

function money(v) {
    return v === null || v === undefined ? "" : inr(v, true);
}

function candidatesSection(b) {
    return `<section><h3>Possible matches in GSTR-2B</h3>
        <p class="note">Shown for your review only. Vishwas never treats a candidate as a match.</p>
        <table class="grid"><thead><tr><th>As written</th><th>Normalised</th><th>Date</th><th class="num">ITC</th><th>Why it is a candidate</th></tr></thead><tbody>
        ${b.candidates.map(c => `<tr><td class="mono">${h(c.invoiceNo)}</td><td class="mono">${h(c.normalisedNo)}</td>
            <td>${day(c.invoiceDate)}</td><td class="num">${inr(c.itc, true)}</td><td>${h((c.reasons || []).join(", "))}</td></tr>`).join("")}
        </tbody></table></section>`;
}

function hypothesesSection(b) {
    if (!b.hypotheses.length) return "";
    return `<section><h3>Likely causes, most likely first</h3>
        ${b.hypotheses.map((x, i) => `<div class="hyp ${i === 0 ? "top" : ""}">
            <span class="rank">#${i + 1}</span><strong>${h(CAUSE_LABEL[x.cause] || x.cause)}</strong>
            <span class="muted small"> · ${h((x.likelihood || "").toLowerCase())} likelihood</span>
            <div class="small" style="margin-top:4px">${h(x.evidence || "")}</div>
        </div>`).join("")}
        ${b.evidenceRefs.length ? `<div class="chips-row">${b.evidenceRefs.map(e => `<span class="evidence-chip">${h(e)}</span>`).join("")}</div>` : ""}
        ${b.guardrails.map(g => `<div class="guardrail">Guardrail: ${h(g)}</div>`).join("")}
    </section>`;
}

function pastCasesSection(b) {
    const rows = b.pastCases || [];
    return `<section><h3>The past cases behind this <span class="muted small">· ${h(b.dimensionLabel)}, this vendor only</span></h3>
        ${rows.length === 0 ? `<p class="note">No past cases on this dimension for this vendor.</p>` : `
        <table class="grid"><thead><tr><th>Month</th><th>Invoice</th><th class="num">Amount (ITC)</th><th>Outcome</th><th class="num">Months late</th></tr></thead><tbody>
        ${rows.map(p => `<tr><td>${shortMonth(p.period)}</td><td class="mono">${h(p.invoiceNo)}</td><td class="num">${inr(p.exposure)}</td>
            <td>${h(p.status === "WRITTEN_OFF" ? "ITC reversed (confirmed loss " + inr(p.confirmedLoss) + ")"
                : p.outcome ? OUTCOME_LABEL[p.outcome] || p.outcome : "Still open")}</td>
            <td class="num">${p.monthsLate ?? ""}</td></tr>`).join("")}
        </tbody></table>`}
    </section>`;
}

function confidenceSection(b) {
    return `<section><h3>Confidence</h3>
        <div class="confidence"><span class="level">${confidence(b.confidenceLevel)}</span><span>${h(b.confidenceText)}</span></div>
        <p class="note">Confidence reflects how much history exists and how consistent it is. It is shown apart from the
            history itself. Past reliability never proves today's invoice is correct.</p>
    </section>`;
}

function memorySection(b) {
    if (!b.memoryFacts.length && !b.vendorSummary) return "";
    return `<section><h3>What memory cited</h3>
        ${b.vendorSummary ? `<p>${h(b.vendorSummary)}</p>` : ""}
        ${b.memoryFacts.length ? `<details><summary class="small">${b.memoryFacts.length} memories used</summary><ul class="facts">
            ${b.memoryFacts.map(f => `<li><span class="ctx">${h(f.context || f.type || "")}</span>${h(f.text)}
                ${f.occurred ? `<span class="muted small"> · ${day(f.occurred)}</span>` : ""}</li>`).join("")}</ul></details>` : ""}
    </section>`;
}

function communicationsSection(b) {
    const comms = (b.communications || []).slice(-5).reverse();
    if (!comms.length) return "";
    return `<section><h3>Vendor communication <span class="muted small">· latest first</span></h3>
        <ul class="timeline">${comms.map(c => `<li class="${c.promiseStatus === "BROKEN" ? "bad" : c.promiseStatus === "KEPT" ? "good" : ""}">
            <div class="when">${day(c.at)} · ${h(c.direction === "OUT" ? "sent" : "received")} · ${h(c.channel.toLowerCase())}</div>
            ${h(c.summary)}
            ${c.promiseBy ? `<div class="small"><strong>Promise:</strong> by ${day(c.promiseBy)} · ${h((c.promiseStatus || "").toLowerCase())}</div>` : ""}
            ${c.attachment ? `<div class="small"><a href="/api/letters/${encodeURIComponent(c.attachment)}" target="_blank" rel="noopener">Open letter (PDF)</a></div>` : ""}
        </li>`).join("")}</ul></section>`;
}

function trackRecordSection(b) {
    const recs = (b.recommendations || []).filter(x => x.id !== b.row.recommendationId);
    if (!recs.length) return "";
    return `<section><h3>Vishwas's track record here</h3>
        <table class="grid"><thead><tr><th>When</th><th>Said</th><th>Accountant</th><th>Proved</th></tr></thead><tbody>
        ${recs.map(x => `<tr><td>${day(x.at)}</td><td>${badge(x.category)} ${h(CAUSE_LABEL[x.topCause] || "")}</td>
            <td>${h((x.decision || "").toLowerCase())}${x.reason ? " · " + h(x.reason.toLowerCase().replace("_", " ")) : ""}</td>
            <td>${x.correct === true ? "right" : x.correct === false ? `wrong (${h(OUTCOME_LABEL[x.actualOutcome] || x.actualOutcome)})` : "not yet"}</td></tr>`).join("")}
        </tbody></table></section>`;
}

function decisionSection(b) {
    const r = b.row;
    const resolved = r.status === "RESOLVED" || r.status === "WRITTEN_OFF";
    const baseline = r.baselineAction ? `<div class="baseline-box"><strong>Without memory</strong>${r.baselineCategory ? " · " + h(r.baselineCategory) : ""}<br>${h(r.baselineAction)}</div>` : "";
    if (!r.recommendationId) {
        return `<section><h3>Suggested next step</h3><p class="muted">Advice is still being prepared.</p>${baseline}</section>`;
    }
    const decided = r.decision ? `<div class="decided">You ${h(r.decision.toLowerCase())} this recommendation.</div>` : "";
    const form = r.decision || resolved ? "" : `
        <div class="decision">
            <button class="primary" data-decide="ACCEPTED">Approve</button>
            <button class="secondary" data-decide="MODIFIED">Modify</button>
            <button class="secondary" data-decide="REJECTED">Reject</button>
            <label class="small">Reason
                <select id="decision-reason">${REASONS.map(([v, l]) => `<option value="${v}">${l}</option>`).join("")}</select></label>
            <textarea id="decision-note" placeholder="Optional note (why?)" maxlength="500"></textarea>
            <textarea id="decision-step" placeholder="Your modified next step (required for Modify)" maxlength="500" hidden></textarea>
        </div>`;
    return `<section><h3>Suggested next step</h3>
        <div class="two-col"><div><p class="next-step">${h(r.nextStep || "")}</p>${decided}</div>${baseline}</div>
        ${form}</section>`;
}

function wire(b, period) {
    const root = panelBody();
    root.querySelector("#brief-vendor")?.addEventListener("click", async () => {
        const { openVendor } = await import("./vendor.js");
        openVendor(b.row.vendorGstin);
    });
    const reason = root.querySelector("#decision-reason");
    if (reason) reason.value = defaultReason(b);
    root.querySelectorAll("[data-decide]").forEach(btn => btn.addEventListener("click", async () => {
        const decision = btn.dataset.decide;
        const step = root.querySelector("#decision-step");
        if (decision === "MODIFIED" && step.hidden) {
            step.hidden = false;
            step.value = b.row.nextStep || "";
            step.focus();
            return;
        }
        btn.disabled = true;
        try {
            await api(`/api/recommendations/${b.row.recommendationId}/decision`, {
                method: "POST",
                body: {
                    decision,
                    reason: root.querySelector("#decision-reason").value,
                    note: root.querySelector("#decision-note").value || null,
                    modifiedStep: decision === "MODIFIED" ? step.value : null,
                },
            });
            banner(`Recorded: ${decision.toLowerCase()}. Vishwas will remember this and check it against the outcome.`, "info");
            document.dispatchEvent(new CustomEvent("vishwas:changed"));
            openBrief(b.row.id, period);
        } catch (e) {
            banner(e.message);
            btn.disabled = false;
        }
    }));
}

function defaultReason(b) {
    const cause = b.hypotheses[0]?.cause;
    return { TIMING_DIFFERENCE: "TIMING_DIFFERENCE", DATA_ENTRY_TYPO: "TYPO", DUPLICATE_BOOKING: "DUPLICATE",
        VENDOR_NOT_FILING: "MISSING_DOCUMENT", CREDIT_NOTE_EXPECTED: "INCORRECT_AMOUNT" }[cause] || "OTHER";
}

export { closePanel };
