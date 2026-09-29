// Entry point: theme, status chips, step wiring, and resuming whatever was in flight after a reload.
import { api } from "./api.js";
import { h } from "./fmt.js";
import { banner, confirmDialog, initPanel } from "./ui.js";
import { followAdvice, followHistory, initFiles, initStepToggles, loadHistory, loadWorkspace, reconcile, renderSteps, state } from "./steps.js";
import { initDrawer, invalidateDrawer } from "./drawer.js";

const $ = sel => document.querySelector(sel);

function initTheme() {
    let saved = null;
    try { saved = localStorage.getItem("vishwas-theme"); } catch { /* storage unavailable */ }
    if (saved) document.documentElement.dataset.theme = saved;
    $("#theme-toggle").addEventListener("click", () => {
        const dark = matchMedia("(prefers-color-scheme: dark)").matches;
        const current = document.documentElement.dataset.theme || (dark ? "dark" : "light");
        const next = current === "dark" ? "light" : "dark";
        document.documentElement.dataset.theme = next;
        try { localStorage.setItem("vishwas-theme", next); } catch { /* storage unavailable */ }
    });
}

function chip(label, cls, title) {
    return `<span class="chip ${cls}" title="${h(title || "")}"><span class="dot"></span>${h(label)}</span>`;
}

export function renderChips(s) {
    const memoryCls = s.memory === "READY" ? "ok" : s.memory === "CONNECTING" ? "wait" : "off";
    const memoryLabel = { READY: "Memory on", CONNECTING: "Memory connecting", NOT_CONFIGURED: "Memory off", UNREACHABLE: "Memory unreachable" }[s.memory] || s.memory;
    $("#chips").innerHTML = [
        chip(memoryLabel, memoryCls, (s.memoryError || "") + " · bank " + s.bankId),
        chip(s.baseline === "READY" ? "Baseline on" : "Baseline off", s.baseline === "READY" ? "ok" : "off", "No-memory comparison via Groq"),
        chip((s.memories ?? "–") + " memories", "", "Memory units in bank " + s.bankId),
        chip((s.observations ?? "–") + " observations", "", "Consolidated beliefs"),
        chip("bank " + s.bankId, "", "Hindsight bank"),
    ].join("");
    if (s.company) {
        $("#company").textContent = `${s.company.legalName} · GSTIN ${s.company.gstin} · ${s.company.city} · Accountant ${s.company.accountant} · CFO ${s.company.cfo}`;
    }
}

async function refreshStatus() {
    const s = await api("/api/status");
    state.status = s;
    state.period = s.livePeriod;
    state.nextPeriod = s.nextPeriod;
    renderChips(s);
    return s;
}

async function boot() {
    initTheme();
    initPanel();
    initStepToggles();
    initFiles();
    initDrawer(() => state.period);
    $("#load-history").addEventListener("click", loadHistory);
    $("#reconcile").addEventListener("click", reconcile);
    $("#next-month").addEventListener("click", async () => {
        const { nextMonth } = await import("./nextmonth.js");
        nextMonth();
    });
    $("#reset-demo").addEventListener("click", resetDemo);
    initViews();
    document.addEventListener("vishwas:changed", async () => {
        await loadWorkspace().catch(e => banner(e.message));
        invalidateDrawer();
    });

    try {
        const s = await refreshStatus();
        if (s.imported?.includes(s.livePeriod + ":GSTR2B")) {
            const ws = await loadWorkspace();
            if (ws.advice?.status === "RUNNING") followAdvice(ws.advice.runId);
        }
        renderSteps();
        if (s.history?.running || s.history?.memory?.busy) followHistory().catch(e => banner(e.message));
        if (s.imported?.includes(s.nextPeriod + ":GSTR2B")) {
            const { showNextMonthDone } = await import("./nextmonth.js");
            showNextMonthDone();
        }
    } catch (e) {
        banner("Vishwas could not load its status: " + e.message);
    }
    setInterval(() => refreshStatus().catch(() => {}), 10000);
    window.addEventListener("hashchange", route);
    route();
}

/** Tabs: Reconcile (Steps 1 to 4, the default) and the Tier 2/3 views, each loaded when opened. */
function initViews() {
    document.querySelectorAll("#view-tabs button").forEach(b => b.addEventListener("click", () => showView(b.dataset.view)));
}

export async function showView(view) {
    document.querySelectorAll("#view-tabs button").forEach(b => b.classList.toggle("on", b.dataset.view === view));
    document.querySelectorAll(".view").forEach(v => v.hidden = v.id !== "view-" + view);
    if (view === "actions") (await import("./actions.js")).showActionCenter();
    if (view === "assistant") (await import("./assistant.js")).showAssistant();
    if (view === "close") (await import("./close.js")).showClose();
}

/** Deep links: #case/123 opens an investigation brief, #vendor/GSTIN a vendor profile. */
async function route() {
    const [kind, id] = location.hash.replace(/^#/, "").split("/");
    if (kind === "view" && id) {
        showView(id);
    } else if (kind === "case" && id) {
        const { openBrief } = await import("./brief.js");
        openBrief(Number(id), state.period);
    } else if (kind === "vendor" && id) {
        const { openVendor } = await import("./vendor.js");
        openVendor(decodeURIComponent(id));
    }
}

async function resetDemo() {
    const ok = await confirmDialog("Reset the demo?",
        "This deletes every reconciliation in the database and clears the memory bank '" + (state.status?.bankId || "") + "'. It cannot be undone.",
        "Reset everything");
    if (!ok) return;
    try {
        const r = await api("/api/demo/reset", { method: "POST", timeoutMs: 120000 });
        banner(r.message, r.memoryCleared || !state.status?.memory || state.status.memory === "NOT_CONFIGURED" ? "info" : "warn");
        setTimeout(() => location.reload(), 900);
    } catch (e) {
        banner(e.message);
    }
}

boot();
