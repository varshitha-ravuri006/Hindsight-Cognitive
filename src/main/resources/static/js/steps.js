// Steps 1 to 3: load history, reconcile August, and the "Money at risk" hero with the case list.
import { api, poll } from "./api.js";
import { badge, confidence, h, inr, month } from "./fmt.js";
import { banner, skeletonLines } from "./ui.js";
import { openBrief } from "./brief.js";
import { openVendor } from "./vendor.js";

export const state = {
    status: null,
    period: "2026-08",
    nextPeriod: "2026-09",
    workspace: null,
    advicePolling: false,
};

const $ = sel => document.querySelector(sel);

// ---------------------------------------------------------------- step state machine

export function stepFlags() {
    const s = state.status;
    if (!s) return {};
    const hist = s.history || {};
    const imported = s.imported || [];
    const loading = hist.running || (hist.memory && hist.memory.busy);
    const historyDone = hist.databaseLoaded && !loading && (hist.memoryLoaded || !hist.memoryEnabled);
    const reconciled = imported.includes(state.period + ":GSTR2B") && (state.workspace?.money?.totalMismatches || 0) > 0;
    const adviceDone = !state.workspace?.advice || state.workspace.advice.status !== "RUNNING";
    const nextDone = imported.includes(state.nextPeriod + ":GSTR2B");
    return { loading, historyDone, reconciled, adviceDone, nextDone, dbLoaded: hist.databaseLoaded };
}

export function renderSteps() {
    const f = stepFlags();
    setStep(1, f.historyDone ? "done" : "active");
    setStep(2, !f.historyDone ? "locked" : f.reconciled ? "done" : "active");
    setStep(3, f.reconciled ? "active" : "locked");
    setStep(4, !f.reconciled || !f.adviceDone ? "locked" : f.nextDone ? "done-open" : "active");
    $("#load-history").disabled = !!f.loading;
    $("#reconcile").disabled = !f.historyDone;
    $("#next-month").disabled = !(f.reconciled && f.adviceDone) || f.nextDone;
    renderHistorySummary();
}

function setStep(n, mode) {
    const el = $("#step-" + n);
    el.classList.remove("active", "done", "locked", "collapsed");
    if (mode === "done") {
        el.classList.add("done");
        if (!el.dataset.userOpen) el.classList.add("collapsed");
    } else if (mode === "done-open") {
        el.classList.add("done");
    } else {
        el.classList.add(mode);
    }
}

export function initStepToggles() {
    document.querySelectorAll(".step-head").forEach(head => {
        head.addEventListener("click", () => {
            const step = head.parentElement;
            if (step.classList.contains("done")) {
                step.classList.toggle("collapsed");
                step.dataset.userOpen = step.classList.contains("collapsed") ? "" : "1";
            }
        });
    });
}

// ---------------------------------------------------------------- Step 1: history

function renderHistorySummary() {
    const hist = state.status?.history;
    const el = $("#step-1-summary");
    if (hist?.summary && hist.databaseLoaded) {
        const memoryNote = !hist.memoryEnabled ? " Memory is off: Vishwas will use textbook rules."
            : hist.memoryLoaded ? " Remembered in Hindsight, month by month." : "";
        el.textContent = hist.summary.line + memoryNote;
    } else {
        el.textContent = "";
    }
}

export async function loadHistory() {
    $("#load-history").disabled = true;
    $("#history-progress").hidden = false;
    try {
        await api("/api/history/load", { method: "POST" });
        await followHistory();
    } catch (e) {
        banner(e.message);
    } finally {
        $("#load-history").disabled = false;
    }
}

export async function followHistory() {
    $("#history-progress").hidden = false;
    const final = await poll("/api/history/status", s => !s.running && !(s.memory && s.memory.busy), {
        everyMs: 1200,
        deadlineMs: 40 * 60 * 1000,
        onTick: renderHistoryProgress,
    });
    if (final.error) banner("History could not be loaded: " + final.error);
    if (final.memory && final.memory.error) banner("Memory load stopped: " + final.memory.error + ". The database history is available; memory will be retried on the next load.", "warn");
    state.status.history = final;
    $("#history-progress").hidden = true;
    renderSteps();
}

function renderHistoryProgress(s) {
    const m = s.memory || {};
    let pct = 5;
    let text = "Replaying April to July in the database…";
    if (s.databaseLoaded) {
        pct = 25;
        text = "Database ready.";
    }
    if (m.busy && m.batches) {
        const within = m.itemsTotal ? m.itemsSent / m.itemsTotal : 0;
        pct = 25 + Math.round(75 * Math.max(within, (m.batch - 1) / m.batches));
        text = `${m.stage} (month ${m.batch} of ${m.batches}) · ${m.itemsSent} of ${m.itemsTotal} memories`;
    } else if (!s.running && s.databaseLoaded) {
        pct = 100;
        text = s.memoryLoaded ? "History remembered." : "Database history ready.";
    }
    $("#history-bar").style.width = pct + "%";
    $("#history-stage").textContent = text;
    if (s.summary) $("#step-1-summary").textContent = s.summary.line;
}

// ---------------------------------------------------------------- Step 2: reconcile

export function initFiles() {
    for (const [inputId, nameId] of [["books-file", "books-name"], ["gstr2b-file", "gstr2b-name"]]) {
        const input = document.getElementById(inputId);
        input.addEventListener("change", () => {
            const f = input.files[0];
            const label = input.closest(".file");
            if (f) {
                document.getElementById(nameId).textContent = "Your file: " + f.name;
                label.classList.add("custom");
            }
        });
    }
}

export async function reconcile() {
    const btn = $("#reconcile");
    btn.disabled = true;
    const started = performance.now();
    $("#reconcile-note").textContent = "Importing and matching…";
    renderCaseSkeleton();
    try {
        const form = new FormData();
        const books = $("#books-file").files[0];
        const gstr2b = $("#gstr2b-file").files[0];
        if (books) form.append("books", books);
        if (gstr2b) form.append("gstr2b", gstr2b);
        const r = await api(`/api/periods/${state.period}/reconcile`, { method: "POST", form, timeoutMs: 90000 });
        const warnings = [...(r.books?.warnings || []), ...(r.gstr2b?.warnings || [])];
        if (warnings.length) banner(`${warnings.length} import warning(s): ${warnings.slice(0, 3).join(" · ")}`, "warn", { timeoutMs: 20000 });
        $("#step-2-summary").textContent = `${r.books.rows} invoices booked, ${r.gstr2b.rows} in GSTR-2B · ${r.exactMatches} matched exactly · `
            + `${r.newMismatches} new mismatches · ${r.judged} older case(s) judged by this GSTR-2B.`;
        $("#reconcile-note").textContent = r.adviceMode === "MEMORY"
            ? "Consulting memory for each vendor in parallel…" : "Memory is off: textbook action for every case.";
        state.status.imported = [...new Set([...(state.status.imported || []), state.period + ":GSTR2B", state.period + ":BOOKS"])];
        await followAdvice(r.adviceRunId, started);
    } catch (e) {
        banner(e.message);
        $("#reconcile-note").textContent = "";
    } finally {
        btn.disabled = false;
    }
}

export async function followAdvice(runId, started = performance.now()) {
    if (state.advicePolling) return;
    state.advicePolling = true;
    try {
        await loadWorkspace();
        renderSteps();
        await poll(`/api/advice-runs/${runId}`, r => r.status !== "RUNNING", {
            everyMs: 900,
            deadlineMs: 4 * 60 * 1000,
            onTick: async run => {
                if (state.workspace) state.workspace.advice = run;
                await loadWorkspace();
            },
        });
        await loadWorkspace();
        const secs = ((performance.now() - started) / 1000).toFixed(1);
        $("#reconcile-note").textContent = `Done in ${secs} s.`;
        const adv = state.workspace?.advice;
        if (adv?.message) banner(adv.message, "warn", { timeoutMs: 20000 });
    } finally {
        state.advicePolling = false;
        renderSteps();
    }
}

// ---------------------------------------------------------------- Step 3: money at risk

export async function loadWorkspace() {
    const ws = await api(`/api/periods/${state.period}/workspace`);
    state.workspace = ws;
    renderWorkspace(ws);
    return ws;
}

function renderCaseSkeleton() {
    $("#money").innerHTML = ["review", "timing", "loss"].map(k =>
        `<div class="money-card ${k}"><div class="amount skeleton">₹00,000</div><div class="label skeleton">loading</div></div>`).join("");
    $("#cases").innerHTML = Array.from({ length: 4 }, () =>
        `<div class="vendor-group"><div class="vendor-head"><h3 class="skeleton">Vendor name here</h3></div>
         <div class="case"><span class="badge pending skeleton">Thinking</span><div>${skeletonLines(2)}</div><div>${skeletonLines(2)}</div></div></div>`).join("");
}

export function renderWorkspace(ws) {
    if (!ws || !ws.money || ws.money.totalMismatches === 0) return;
    const m = ws.money;
    $("#period-label").textContent = ws.periodLabel;
    $("#money").innerHTML = `
        <div class="money-card review"><div class="amount">${inr(m.needsReview)}</div><div class="label">needs review</div>
            <div class="hint">Potential exposure on cases marked Review or Escalate. Not a loss.</div></div>
        <div class="money-card timing"><div class="amount">${inr(m.likelyTiming)}</div><div class="label">likely timing differences</div>
            <div class="hint">Vendors whose missing invoices historically appeared in the next GSTR-2B.</div></div>
        <div class="money-card loss"><div class="amount">${inr(m.confirmedLossToDate)}</div><div class="label">confirmed loss to date</div>
            <div class="hint">ITC actually reversed. ${inr(m.recoveredToDate)} resolved or recovered to date.</div></div>`;
    $("#attention").innerHTML = `<strong>${m.attentionCases} case${m.attentionCases === 1 ? "" : "s"}</strong> need your attention out of `
        + `${m.totalMismatches} mismatches <span class="muted">(${inr(m.potentialExposure)} potential exposure in view)</span>`;
    $("#step-3-summary").textContent = "";
    if (!$("#step-2-summary").textContent) {
        $("#step-2-summary").textContent = `${ws.booksRows} invoices booked, ${ws.gstr2bRows} in GSTR-2B · ${ws.newMismatches} new mismatches this month, plus open cases carried forward.`;
    }
    renderAdviceState(ws.advice);
    $("#cases").innerHTML = ws.vendors.map(vendorGroup).join("");
    $("#cases").querySelectorAll(".case").forEach(row => {
        const open = () => openBrief(Number(row.dataset.id), state.period);
        row.addEventListener("click", open);
        row.addEventListener("keydown", e => { if (e.key === "Enter") open(); });
    });
    $("#cases").querySelectorAll("[data-vendor]").forEach(b => b.addEventListener("click", e => {
        e.stopPropagation();
        openVendor(b.dataset.vendor);
    }));
}

function renderAdviceState(adv) {
    const el = $("#advice-state");
    if (!adv) { el.innerHTML = ""; return; }
    if (adv.status === "RUNNING") {
        const pct = adv.vendorsTotal ? Math.round(100 * adv.vendorsDone / adv.vendorsTotal) : 5;
        el.innerHTML = `<div class="progress"><div class="bar"><span style="width:${Math.max(pct, 5)}%"></span></div>
            <p class="muted">${adv.mode === "MEMORY" ? "Consulting each vendor's memory" : "Applying textbook rules"} · ${adv.vendorsDone} of ${adv.vendorsTotal} vendors</p></div>`;
    } else if (adv.mode === "TEXTBOOK") {
        el.innerHTML = `<div class="banner info">Memory is off, so every case shows the textbook action. Connect Hindsight to see what Vishwas remembers.</div>`;
    } else {
        el.innerHTML = adv.durationMs ? `<p class="muted small">Advice from memory for ${adv.vendorsTotal} vendors in ${(adv.durationMs / 1000).toFixed(1)} s.</p>` : "";
    }
}

function vendorGroup(g) {
    return `<div class="vendor-group">
        <div class="vendor-head">
            <div><h3>${h(g.name)}</h3><span class="gstin">${h(g.gstin)}</span></div>
            <span class="exposure" title="Open potential exposure">${inr(g.exposure)}</span>
            <button class="secondary" data-vendor="${h(g.gstin)}">Vendor profile</button>
        </div>
        ${g.rows.map(caseRow).join("")}
    </div>`;
}

function caseRow(r) {
    const resolved = r.status === "RESOLVED" || r.status === "WRITTEN_OFF";
    const thinking = !r.category && !resolved;
    const explanation = thinking ? skeletonLines(1) : h(r.headline || "");
    const baseline = r.baselineAction ? h(r.baselineAction) : (thinking || !r.category ? skeletonLines(2) : `<span class="muted">No baseline.</span>`);
    return `<div class="case ${resolved ? "resolved" : ""}" data-id="${r.id}" tabindex="0" role="button"
                aria-label="Open investigation brief for ${h(r.invoice)}">
        <div>${resolved ? `<span class="badge pending">${h(r.verdict || r.status)}</span>` : badge(r.category)}</div>
        <div class="what">
            <div class="title">${h(r.invoice)} <span class="tag">${h(r.typeLabel)}</span>
                ${r.carriedForward ? `<span class="tag cf">carried from ${h(r.periodLabel)}</span>` : ""}
                ${r.hasCandidates ? `<span class="tag">possible match</span>` : ""}
                <span class="amt">${inr(r.exposure)}</span></div>
            <div class="expl">${explanation}</div>
            <div class="meta">${confidence(r.confidence)}${r.decision ? `<span>· ${h(r.decision.toLowerCase())}</span>` : ""}
                ${r.source === "TEXTBOOK" ? "<span>· textbook</span>" : ""}${r.rule ? `<span>· rule ${h(r.rule)}</span>` : ""}</div>
        </div>
        <div class="baseline"><span class="lbl">Without memory${r.baselineCategory ? " · " + h(r.baselineCategory) : ""}</span>${baseline}</div>
    </div>`;
}

export { month };
