// Step 4, "Next month arrives": the September GSTR-2B judges August live. Verdicts, whether Vishwas's own
// recommendations were right, and each vendor's dimension cards before and after, with the change highlighted.
import { api } from "./api.js";
import { CATEGORY_LABEL, DIMENSION_LABEL, h, inr, OUTCOME_LABEL } from "./fmt.js";
import { banner, skeletonLines } from "./ui.js";
import { loadWorkspace, renderSteps, state } from "./steps.js";
import { dimCard } from "./vendor.js";

const $ = sel => document.querySelector(sel);

export async function nextMonth() {
    const btn = $("#next-month");
    btn.disabled = true;
    $("#next-month-result").innerHTML = `<div style="margin-top:14px">${skeletonLines(4)}</div>`;
    try {
        const r = await api(`/api/periods/${state.nextPeriod}/next-month`, { method: "POST", timeoutMs: 90000 });
        state.status.imported = [...new Set([...(state.status.imported || []), state.nextPeriod + ":GSTR2B"])];
        render(r);
        await loadWorkspace();
        document.dispatchEvent(new CustomEvent("vishwas:changed"));
        if (r.memoryOn) watchBeliefs(r);
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
        ${r.memoryOn ? `<section style="margin-top:14px" id="belief-live"><h3>Memory is updating its beliefs</h3>
            <p class="note">Hindsight consolidates the new outcomes into observations in the background.</p>
            <div id="belief-live-body">${skeletonLines(2)}</div></section>` : ""}`;
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

/** Poll the belief history of the vendor whose view should move most, until memory has consolidated. */
async function watchBeliefs(r) {
    const target = r.vendors.find(v => v.changedDimensions.includes("TIMING")) || r.vendors[0];
    if (!target) return;
    const dim = target.changedDimensions.includes("TIMING") ? "TIMING" : target.changedDimensions[0];
    const body = () => document.getElementById("belief-live-body");
    let initial = null;
    const deadline = Date.now() + 4 * 60 * 1000;
    while (Date.now() < deadline && body()) {
        try {
            const res = await api(`/api/vendors/${encodeURIComponent(target.gstin)}/belief-history?dimension=${dim}`, { timeoutMs: 30000 });
            const hist = res.history;
            const text = hist ? hist.current : null;
            if (initial === null) initial = text || "";
            if (text && text !== initial) {
                body().innerHTML = `<p><strong>${h(target.vendor)} · ${h(DIMENSION_LABEL[dim])}</strong></p>
                    <div class="belief"><div class="when">Before</div>${h(initial || "No belief yet.")}</div>
                    <div class="belief current"><div class="when">Now</div>${h(text)}</div>`;
                return;
            }
            body().innerHTML = `<p class="note">Waiting for Hindsight to consolidate ${h(target.vendor)}'s ${h(DIMENSION_LABEL[dim].toLowerCase())}…</p>
                ${text ? `<div class="belief"><div class="when">Current belief</div>${h(text)}</div>` : ""}`;
        } catch (e) {
            if (body()) body().innerHTML = `<p class="note">Memory did not answer: ${h(e.message)}</p>`;
            return;
        }
        await new Promise(res => setTimeout(res, 6000));
    }
    if (body()) body().innerHTML += `<p class="note">Consolidation is still running; open the vendor profile later to see the updated belief.</p>`;
}
