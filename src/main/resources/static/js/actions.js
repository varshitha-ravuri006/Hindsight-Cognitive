// Action center: every open case with its working state, owner, due date and reminders, plus Vishwas's
// recommendation accuracy and the two mental models. Opens the investigation brief to work a case.
import { api } from "./api.js";
import { badge, CATEGORY_LABEL, dateTime, day, h, inr, markdown } from "./fmt.js";
import { banner, skeletonLines } from "./ui.js";

const STATE_ORDER = ["DETECTED", "INVESTIGATING", "WAITING_FOR_VENDOR", "VENDOR_RESPONDED", "ACCOUNTANT_REVIEW", "ESCALATED", "RESOLVED"];
const STATE_LABEL = {
    DETECTED: "Detected", INVESTIGATING: "Investigating", WAITING_FOR_VENDOR: "Waiting for vendor",
    VENDOR_RESPONDED: "Vendor responded", ACCOUNTANT_REVIEW: "Accountant review", RESOLVED: "Resolved", ESCALATED: "Escalated",
};
let filter = null;

export async function showActionCenter() {
    const root = document.getElementById("view-actions");
    root.innerHTML = `<section class="step active" style="padding:18px 20px">${skeletonLines(6)}</section>`;
    try {
        const [board, accuracy, models] = await Promise.all([
            api("/api/action-center"),
            api("/api/learning/accuracy"),
            api("/api/memory/mental-models", { timeoutMs: 20000 }).catch(() => ({ available: false })),
        ]);
        root.innerHTML = render(board, accuracy, models);
        wire(root, board);
    } catch (e) {
        root.innerHTML = `<div class="banner">${h(e.message)}</div>`;
    }
}

function render(b, accuracy, models) {
    const rows = filter ? b.rows.filter(r => r.state === filter) : b.rows;
    return `
    <section class="step active" style="padding:18px 20px">
        <h2>Action center <span class="muted small">· ${b.rows.length} open case${b.rows.length === 1 ? "" : "s"} · today ${day(b.today)}</span></h2>
        <div class="summary-row">
            ${STATE_ORDER.filter(s => s !== "RESOLVED").map(s => `<button class="stat ${filter === s ? "warn" : ""}" data-filter="${s}">
                <div class="n">${b.counts[s] || 0}</div><div class="small muted">${STATE_LABEL[s]}</div></button>`).join("")}
            <div class="stat ${b.overdue ? "warn" : ""}"><div class="n">${b.overdue}</div><div class="small muted">overdue</div></div>
            <div class="stat ${b.remindersDue ? "warn" : ""}"><div class="n">${b.remindersDue}</div><div class="small muted">reminders due</div></div>
        </div>
        ${filter ? `<p class="small">Showing ${h(STATE_LABEL[filter])} · <span class="link" data-filter="">show all</span></p>` : ""}
        <table class="grid board"><thead><tr><th>Vendor</th><th>Invoice</th><th class="num">Exposure</th><th>Vishwas</th>
            <th>State</th><th>Owner</th><th>Due</th><th>Reminder</th></tr></thead><tbody>
        ${rows.map(r => `<tr data-case="${r.id}" tabindex="0">
            <td>${h(r.vendor)}<div class="small muted">${h(r.type)} · ${h(r.period)}</div></td>
            <td class="mono">${h(r.invoice)}</td><td class="num">${inr(r.exposure)}</td>
            <td>${r.category ? badge(r.category) : ""}</td>
            <td><span class="state-pill ${h(r.state)}">${h(r.stateLabel)}</span></td>
            <td>${h(r.owner || "–")}</td>
            <td class="${r.overdue ? "overdue" : ""}">${r.dueDate ? day(r.dueDate) : "–"}</td>
            <td>${r.openReminders ? `${day(r.nextReminder)}${r.openReminders > 1 ? ` (+${r.openReminders - 1})` : ""}` : "–"}</td>
        </tr>`).join("") || `<tr><td colspan="8" class="muted">Nothing here.</td></tr>`}
        </tbody></table>
    </section>

    <div class="two-col">
        <section class="step active" style="padding:16px 20px"><h3>Recommendation accuracy</h3>
            <p class="note">Computed in the database: each recommendation against what the case actually turned out to be.</p>
            <div class="accuracy">${accuracy.map(a => `<div class="acc"><div class="small muted">${h(CATEGORY_LABEL[a.category])}</div>
                <div class="pct">${a.judged ? Math.round(100 * a.correct / a.judged) + "%" : "–"}</div>
                <div class="small">${a.judged ? `${a.correct} of ${a.judged} right` : "not judged yet"}</div></div>`).join("")}</div>
        </section>
        <section class="step active" style="padding:16px 20px"><h3>Mental models</h3>
            ${!models.available ? `<p class="note">Memory is off.</p>` : models.models.map(m => `<details style="margin:6px 0">
                <summary><strong>${h(m.name)}</strong> <span class="muted small">· ${m.lastRefreshedAt ? "refreshed " + dateTime(m.lastRefreshedAt) : "not refreshed yet"}${m.stale ? " · newer memories since" : ""}</span></summary>
                <div class="markdown">${m.content ? markdown(m.content) : "<p class='muted'>No content yet.</p>"}</div></details>`).join("")}
            ${models.available ? `<button class="secondary" id="refresh-models">Refresh now</button>` : ""}
        </section>
    </div>`;
}

function wire(root, board) {
    root.querySelectorAll("[data-filter]").forEach(b => b.addEventListener("click", () => {
        filter = b.dataset.filter && b.dataset.filter !== filter ? b.dataset.filter : null;
        showActionCenter();
    }));
    root.querySelectorAll("[data-case]").forEach(tr => {
        const open = async () => {
            const { openBrief } = await import("./brief.js");
            const row = board.rows.find(r => String(r.id) === tr.dataset.case);
            openBrief(Number(tr.dataset.case), row?.period, { onClose: showActionCenter });
        };
        tr.addEventListener("click", open);
        tr.addEventListener("keydown", e => { if (e.key === "Enter") open(); });
    });
    root.querySelector("#refresh-models")?.addEventListener("click", async () => {
        try {
            await api("/api/memory/mental-models/refresh", { method: "POST" });
            banner("Refresh requested. Mental models update in the background.", "info");
        } catch (e) {
            banner(e.message);
        }
    });
}
