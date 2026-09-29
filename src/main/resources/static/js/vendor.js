// Vendor profile: dimension cards (never one trust score), what memory learned, how that view changed
// month by month, and the chronological timeline.
import { api } from "./api.js";
import { dateTime, day, DIMENSION_LABEL, h, inr, OUTCOME_LABEL } from "./fmt.js";
import { openPanel, panelBody, skeletonLines } from "./ui.js";

export async function openVendor(gstin, { highlight = [] } = {}) {
    openPanel(`<h2 class="skeleton">Loading vendor</h2>${skeletonLines(8)}`);
    try {
        const p = await api(`/api/vendors/${encodeURIComponent(gstin)}`);
        panelBody().innerHTML = render(p, highlight);
        wire(p);
        loadLearned(p);
        import("./curation.js").then(m => m.initVendorMemory(p));
        loadBeliefHistory(p.gstin, highlight[0] || firstInterestingDimension(p));
    } catch (e) {
        panelBody().innerHTML = `<div class="banner">${h(e.message)}</div>`;
    }
}

function firstInterestingDimension(p) {
    const d = p.dimensions.find(x => x.cases > 0 && x.dimension !== "RESPONSIVENESS");
    return d ? d.dimension : "TIMING";
}

function render(p, highlight) {
    return `
    <p class="muted small mono">${h(p.gstin)}</p>
    <h2>${h(p.legalName)}</h2>
    <p class="muted">${h(p.city || "")}${p.supplies ? " · " + h(p.supplies) : ""}${p.contact ? " · " + h(p.contact) : ""}</p>
    <div class="money" style="margin-top:12px">
        <div class="money-card review"><div class="amount">${inr(p.openExposure)}</div><div class="label">open potential exposure</div></div>
        <div class="money-card timing"><div class="amount">${inr(p.recovered)}</div><div class="label">resolved or recovered</div></div>
        <div class="money-card loss"><div class="amount">${inr(p.confirmedLoss)}</div><div class="label">confirmed loss</div></div>
    </div>

    <section><h3>Dimension by dimension <span class="muted small">· from the database, never one trust score</span></h3>
        <div class="dims">${p.dimensions.map(d => dimCard(d, highlight.includes(d.dimension))).join("")}</div>
    </section>

    <section><h3>What Vishwas learned <span class="muted small">· Hindsight observations for this GSTIN only</span></h3>
        <div id="learned">${skeletonLines(3)}</div>
    </section>

    <section><h3>How Vishwas's view of this vendor changed</h3>
        <label class="small">Dimension
            <select id="belief-dim">${Object.entries(DIMENSION_LABEL).map(([k, v]) => `<option value="${k}">${v}</option>`).join("")}</select>
        </label>
        <div id="belief-history" style="margin-top:10px">${skeletonLines(3)}</div>
    </section>

    ${trackRecord(p.trackRecord)}

    <section><h3>Knowledge page <span class="muted small">· Vendors/${h(p.legalName)}, written by Hindsight from this vendor's memory</span></h3>
        <div class="form-row"><a class="secondary" style="text-decoration:none;display:inline-block" href="/api/vendors/${encodeURIComponent(p.gstin)}/dossier" download>Export vendor dossier</a>
            <button class="secondary" id="page-refresh">Refresh page</button></div>
        <div id="knowledge-page">${skeletonLines(2)}</div>
    </section>

    <section><h3>Letters and e-mails</h3>
        <p class="note">Upload a vendor's letter or e-mail as PDF. It joins the vendor's thread and Hindsight reads its own words; a promised date is checked against the next GSTR-2B.</p>
        <form id="letter-form" class="form-row">
            <label class="field">PDF<input type="file" name="file" accept="application/pdf" required></label>
            <label class="field">What it says<input name="summary" required maxlength="1000" style="min-width:280px" placeholder="Promises to file KPL/0631 by 10 Oct"></label>
            <label class="field">Received<input type="date" name="receivedOn"></label>
            <label class="field">Promised by<input type="date" name="promiseBy"></label>
            <label class="field">Invoices<input name="invoices" placeholder="KPL/0631, KPL/0589"></label>
            <button class="secondary" type="submit">Upload</button>
        </form>
    </section>

    <section><h3>Correct this history</h3>
        <p class="note">If memory holds something wrong about this vendor, correct the fact itself. Every change is audited
            (who, when, why) and Hindsight rebuilds the vendor's beliefs from the corrected facts.</p>
        <div id="curation">${skeletonLines(3)}</div>
    </section>

    <section><h3>Timeline</h3>
        <ul class="timeline">${p.timeline.map(t => `<li class="${h(t.tone)}">
            <div class="when">${dateTime(t.at)}${t.dimension ? " · " + h(DIMENSION_LABEL[t.dimension] || t.dimension) : ""}</div>
            <strong>${h(t.title)}</strong>${t.amount ? ` <span class="muted">${inr(t.amount)}</span>` : ""}
            ${t.detail ? `<div class="small">${h(t.detail)}</div>` : ""}
        </li>`).join("")}</ul>
    </section>
    <p class="disclaimer">Informational only, not tax advice. Past reliability never proves today's invoice is correct.</p>`;
}

export function dimCard(d, changed = false) {
    const stats = [];
    if (d.cases) stats.push(`${d.cases} ${d.dimension === "RESPONSIVENESS" ? "follow-up" : "case"}${d.cases === 1 ? "" : "s"}`);
    if (d.averageDaysToResolve != null) stats.push(`${Math.round(d.averageDaysToResolve)} ${d.dimension === "RESPONSIVENESS" ? "days to reply" : "days to resolve"}`);
    if (Number(d.openExposure) > 0) stats.push(`${inr(d.openExposure)} open`);
    if (Number(d.confirmedLoss) > 0) stats.push(`${inr(d.confirmedLoss)} lost`);
    if (Number(d.recovered) > 0) stats.push(`${inr(d.recovered)} resolved`);
    const outcomes = Object.entries(d.outcomes || {}).filter(([, n]) => n > 0)
        .map(([o, n]) => `${n} ${(OUTCOME_LABEL[o] || o.toLowerCase()).toLowerCase()}`).join(", ");
    return `<div class="dim ${d.cases ? "" : "none"} ${changed ? "changed" : ""}" data-dim="${h(d.dimension)}">
        <div class="dim-name">${h(d.label)} <span class="rel ${h(d.reliability)}" title="${h(d.reliabilityText)}">${h(relLabel(d.reliability))}</span></div>
        <div class="q">${h(d.question)}</div>
        <div class="headline">${h(d.headline)}</div>
        ${outcomes ? `<div class="small muted">${h(outcomes)}</div>` : ""}
        <div class="stats">${stats.map(s => `<span>${h(s)}</span>`).join("")}</div>
    </div>`;
}

function relLabel(level) {
    return { HIGH: "consistent", MEDIUM: "mixed", LOW: "thin history", NONE: "no history" }[level] || level;
}

function trackRecord(t) {
    if (!t || !t.recommendations) return "";
    return `<section><h3>Vishwas's track record with this vendor</h3>
        <p>${t.recommendations} recommendation${t.recommendations === 1 ? "" : "s"} · ${t.judged} judged by later data ·
        <strong>${t.correct} right</strong>, ${t.judged - t.correct} wrong · accountant accepted ${t.accepted}, modified ${t.modified}, rejected ${t.rejected}</p>
    </section>`;
}

function wire(p) {
    const select = panelBody().querySelector("#belief-dim");
    select.value = firstInterestingDimension(p);
    select.addEventListener("change", () => loadBeliefHistory(p.gstin, select.value));
}

async function loadLearned(p) {
    const el = panelBody().querySelector("#learned");
    try {
        const r = await api(`/api/vendors/${encodeURIComponent(p.gstin)}/learned`, { timeoutMs: 45000 });
        if (!el.isConnected) return;
        if (!r.available) {
            el.innerHTML = `<p class="note">${h(r.message)}</p>`;
            return;
        }
        if (!r.beliefs.length) {
            el.innerHTML = `<p class="note">No beliefs formed yet. They appear once Hindsight has consolidated the history.</p>`;
            return;
        }
        el.innerHTML = `<ul class="facts">${r.beliefs.map(b => `<li><span class="ctx">${h(DIMENSION_LABEL[b.dimension] || "vendor")}</span>${h(b.text)}</li>`).join("")}</ul>`;
    } catch (e) {
        if (el.isConnected) el.innerHTML = `<p class="note">Memory did not answer: ${h(e.message)}</p>`;
    }
}

export async function loadBeliefHistory(gstin, dimension) {
    const el = panelBody().querySelector("#belief-history");
    if (!el) return;
    const select = panelBody().querySelector("#belief-dim");
    if (select) select.value = dimension;
    el.innerHTML = skeletonLines(3);
    try {
        const r = await api(`/api/vendors/${encodeURIComponent(gstin)}/belief-history?dimension=${dimension}`, { timeoutMs: 45000 });
        if (!el.isConnected) return;
        if (!r.available) {
            el.innerHTML = `<p class="note">${h(r.message)}</p>`;
            return;
        }
        const hist = r.history;
        if (!hist) {
            el.innerHTML = `<p class="note">Vishwas has not formed a belief about ${h(DIMENSION_LABEL[dimension])} for this vendor.</p>`;
            return;
        }
        el.innerHTML = hist.versions.map((v, i) => {
            const current = i === hist.versions.length - 1;
            return `<div class="belief ${current ? "current" : ""}">
                <div class="when">${current ? "Now" : "Earlier"}${v.becauseOf ? " · after " + h(v.becauseOf) : ""}${v.changedAt ? " · " + day(v.changedAt) : ""}</div>
                <div>${h(v.text || "")}</div>
                ${v.newFacts && v.newFacts.length ? `<div class="small muted">because: ${h(v.newFacts.slice(0, 2).join(" · "))}</div>` : ""}
            </div>`;
        }).join("");
    } catch (e) {
        if (el.isConnected) el.innerHTML = `<p class="note">Memory did not answer: ${h(e.message)}</p>`;
    }
}
