// Step 4, "Next month arrives": the September GSTR-2B judges August live. Verdicts, whether Vishwas's own
// recommendations were right, and each vendor's dimension cards before and after, with the change highlighted.
import { api, pace } from "./api.js";
import { CATEGORY_LABEL, DIMENSION_LABEL, h, inr, OUTCOME_LABEL } from "./fmt.js";
import { banner, skeletonLines } from "./ui.js";
import { loadWorkspace, renderSteps, state } from "./steps.js";
import { dimCard } from "./vendor.js";

const $ = sel => document.querySelector(sel);

export async function nextMonth() {
    const btn = $("#next-month");
    btn.disabled = true;
    $("#next-month-result").innerHTML = `<div style="margin-top:14px">${skeletonLines(4)}</div>`;
    // Snapshot today's beliefs in parallel, so the "before" is what memory believed after August.
    const candidates = beliefTargets(state.workspace);
    const before = snapshotBeliefs(candidates);
    try {
        const r = await api(`/api/periods/${state.nextPeriod}/next-month`, { method: "POST", timeoutMs: 90000 });
        state.status.imported = [...new Set([...(state.status.imported || []), state.nextPeriod + ":GSTR2B"])];
        render(r);                       // instant: verdicts and dimension cards come from the ledger
        loadWorkspace().then(() => document.dispatchEvent(new CustomEvent("vishwas:changed"))).catch(() => {});
        if (r.memoryOn) watchConsolidation(r, candidates, before);
    } catch (e) {
        banner(e.message);
        $("#next-month-result").innerHTML = "";
        btn.disabled = false;
    } finally {
        renderSteps();
    }
}

/** After a reload: the verdicts are in the database; the before/after cards were a live moment. */
export async function showNextMonthDone() {
    try {
        const [verdicts, accuracy] = await Promise.all([
            api(`/api/periods/${state.nextPeriod}/verdicts`),
            api("/api/learning/accuracy"),
        ]);
        $("#step-4-summary").textContent = `${verdicts.length} open case(s) judged by the September 2026 GSTR-2B.`;
        $("#next-month-result").innerHTML = verdictList(verdicts) + accuracyBlock(null, accuracy)
            + `<p class="note">Open any vendor profile to see its updated dimension cards and how Vishwas's view changed.</p>`;
    } catch (e) {
        banner(e.message);
    }
}

function render(r) {
    const right = r.recommendationsRight;
    $("#step-4-summary").textContent = `${r.verdicts.length} open case(s) judged live · ${r.recommendationsJudged} recommendation(s) checked, `
        + `${right} proved right · ${r.promisesSettled} promise(s) settled${r.memoryOn ? ` · ${r.memoriesQueued} new memories` : ""}.`;
    $("#next-month-result").innerHTML = `
        ${verdictList(r.verdicts)}
        ${accuracyBlock(r.accuracyBefore, r.accuracyAfter)}
        <section style="margin-top:14px"><h3>How each vendor's picture changed</h3>
            <p class="note">Dimension cards before and after this GSTR-2B. Highlighted cards changed.</p>
            ${r.vendors.map(vendorChange).join("") || "<p class='muted'>No vendor profile changed.</p>"}
        </section>
        ${r.memoryOn ? `<section style="margin-top:14px" id="belief-live">
            <h3><span class="pulse-dot"></span> <span id="consolidation-title">Memory consolidating…</span>
                <span class="muted small" id="consolidation-timer">0:00</span></h3>
            <p class="note" id="consolidation-stage">Sending the September outcomes to Hindsight.</p>
            <div id="belief-live-body"></div></section>` : ""}`;
}

function verdictList(verdicts) {
    if (!verdicts.length) return `<p class="muted">No open case was resolved by this GSTR-2B.</p>`;
    return `<section style="margin-top:14px"><h3>What the August cases turned out to be</h3>
        <div class="verdicts">${verdicts.map(v => `<div class="v">
            <div><span class="verdict-badge ${h(v.outcome)}">${h(OUTCOME_LABEL[v.outcome] || v.outcome)}</span></div>
            <div><strong>${h(v.vendor)}</strong> · <span class="mono">${h(v.invoice)}</span>
                <span class="muted small">(${h(v.period)})</span><div class="small muted">${h(v.note || "")}</div></div>
            <div class="mono">${v.outcome === "UNRESOLVED_AT_RISK" ? inr(v.exposure) + " at risk" : inr(v.recovered) + " resolved"}</div>
        </div>`).join("")}</div></section>`;
}

function accuracyBlock(before, after) {
    const byCat = list => Object.fromEntries((list || []).map(a => [a.category, a]));
    const b = byCat(before);
    return `<section style="margin-top:14px"><h3>Recommendation accuracy <span class="muted small">· computed in the database from real outcomes</span></h3>
        <div class="accuracy">${(after || []).filter(a => a.judged > 0).map(a => {
            const pct = Math.round(100 * a.correct / a.judged);
            const was = b[a.category];
            const delta = was && was.judged !== a.judged ? ` <span class="muted small">(was ${was.correct}/${was.judged})</span>` : "";
            return `<div class="acc"><div class="small muted">${h(CATEGORY_LABEL[a.category])}</div>
                <div class="pct">${pct}%</div><div class="small">${a.correct} of ${a.judged} right${delta}</div></div>`;
        }).join("") || "<p class='muted'>No recommendation has been judged yet.</p>"}</div></section>`;
}

function vendorChange(v) {
    const changed = new Set(v.changedDimensions);
    const pick = list => list.filter(d => changed.has(d.dimension));
    return `<div class="vendor-group" style="padding:12px 14px">
        <h3>${h(v.vendor)} <span class="muted small mono">${h(v.gstin)}</span>
            <span class="muted small">· ${v.changedDimensions.map(d => h(DIMENSION_LABEL[d] || d)).join(", ")}</span></h3>
        <div class="before-after" style="margin-top:8px">
            <div><div class="small muted">Before</div><div class="dims" style="grid-template-columns:1fr">${pick(v.before).map(d => dimCard(d)).join("")}</div></div>
            <div><div class="small muted">After</div><div class="dims" style="grid-template-columns:1fr">${pick(v.after).map(d => dimCard(d, true)).join("")}</div></div>
        </div></div>`;
}

/** Vendors whose belief should move: those with open cases now, strictest and largest first (max 3). */
function beliefTargets(ws) {
    if (!ws) return [];
    const picks = [];
    for (const g of ws.vendors) {
        const open = g.rows.filter(r => (r.status === "OPEN" || r.status === "AT_RISK") && Number(r.exposure) > 0);
        if (!open.length) continue;
        const main = open.find(r => r.dimension === "TIMING") || open[0];
        const timingCall = open.some(r => r.category === "RECOMMEND" && r.topCause === "TIMING_DIFFERENCE");
        picks.push({ gstin: g.gstin, vendor: g.name, dimension: main.dimension, timingCall, exposure: Number(g.exposure) });
    }
    // The "gets smarter" story first (a timing call the next GSTR-2B will confirm), then the money at risk.
    return picks.filter(p => p.dimension !== "DUPLICATES" && p.dimension !== "INVOICE_FORMAT")
        .sort((a, b) => (b.timingCall - a.timingCall) || (b.exposure - a.exposure)).slice(0, 3);
}

function snapshotBeliefs(targets) {
    return Promise.all(targets.map(t => api(`/api/vendors/${encodeURIComponent(t.gstin)}/belief-history?dimension=${t.dimension}`, { timeoutMs: 30000 })
        .then(r => ({ ...t, history: r.history }))
        .catch(() => ({ ...t, history: null }))));
}

const STAGE_TEXT = {
    EXTRACTING: "Extracting facts from the September outcomes…",
    CONSOLIDATING: "Consolidating the new facts into each vendor's beliefs…",
    REFRESHING_MODELS: "Beliefs updated. Refreshing the Money-at-risk briefing and Vendor watchlist…",
    SETTLED: "Memory is up to date.",
    OFF: "Memory is off.",
};

/** Poll Hindsight's operations until memory has digested September, then swap in the updated beliefs. */
async function watchConsolidation(r, candidates, beforePromise) {
    const since = r.memorySubmittedAt;
    const started = Date.now();
    const timer = setInterval(() => {
        const el = document.getElementById("consolidation-timer");
        if (!el) return clearInterval(timer);
        el.textContent = clock(Date.now() - started);
    }, 1000);
    const deadline = started + 15 * 60 * 1000;
    let status = null;
    try {
        while (Date.now() < deadline && document.getElementById("belief-live")) {
            try {
                status = await api(`/api/memory/settle?since=${encodeURIComponent(since)}`, { timeoutMs: 20000 });
                const stage = document.getElementById("consolidation-stage");
                if (stage) stage.textContent = STAGE_TEXT[status.stage] || status.message;
                if (status.stage === "SETTLED" || status.stage === "OFF") break;
            } catch { /* transient: keep polling */ }
            await new Promise(res => setTimeout(res, pace(3000)));
        }
    } finally {
        clearInterval(timer);
    }
    const title = document.getElementById("consolidation-title");
    if (!title) return;
    const took = clock(Date.now() - started);
    if (!status || status.stage !== "SETTLED") {
        title.textContent = "Memory is still consolidating";
        document.getElementById("consolidation-stage").textContent = "This is taking longer than usual; open a vendor profile later to see the updated belief.";
        return;
    }
    title.textContent = `Memory consolidated in ${took}`;
    document.querySelector("#belief-live .pulse-dot")?.classList.add("done");
    const before = await beforePromise;
    const changed = new Set(r.vendors.map(v => v.gstin));
    const targets = before.filter(b => changed.has(b.gstin));
    const after = await snapshotBeliefs(targets);
    document.getElementById("belief-live-body").innerHTML = (after.map((a, i) => beliefSwap(targets[i], a)).join("")
        || `<p class="note">No vendor belief changed.</p>`)
        + `<p class="note">These are memory's own words. Exact case counts and amounts are on the dimension cards above, from the ledger.</p>`;
    document.dispatchEvent(new CustomEvent("vishwas:changed"));
}

function beliefSwap(before, after) {
    const was = before.history?.current;
    const now = after.history?.current;
    const newest = after.history?.versions?.[after.history.versions.length - 1];
    return `<div class="vendor-group" style="padding:12px 14px">
        <h3>${h(after.vendor)} <span class="muted small">· ${h(DIMENSION_LABEL[after.dimension] || after.dimension)}</span></h3>
        <div class="before-after" style="margin-top:8px">
            <div class="belief"><div class="when">What memory believed after August</div>${h(was || "No belief yet.")}</div>
            <div class="belief current"><div class="when">Now${newest?.becauseOf ? " · new entry: " + h(newest.becauseOf) : ""}</div>
                ${now && now !== was ? h(now) : h(now || "No belief yet.") + (now === was ? " <span class='muted small'>(unchanged)</span>" : "")}</div>
        </div></div>`;
}

function clock(ms) {
    const s = Math.floor(ms / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
