// Month-end close checklist: what blocks closing the month, who owns each item, and the CFO's final sign-off.
import { api } from "./api.js";
import { dateTime, h } from "./fmt.js";
import { banner, skeletonLines } from "./ui.js";
import { state } from "./steps.js";

export async function showClose(period = state.period || "2026-08") {
    const root = document.getElementById("view-close");
    root.innerHTML = `<section class="step active" style="padding:18px 20px">${skeletonLines(6)}</section>`;
    try {
        const c = await api(`/api/close/${period}`);
        root.innerHTML = render(c);
        wire(root, c);
    } catch (e) {
        root.innerHTML = `<div class="banner">${h(e.message)}</div>`;
    }
}

function render(c) {
    const othersBlocking = c.items.some(x => x.key !== "FINAL_REVIEW" && !x.done);
    return `<section class="step active" style="padding:18px 20px">
        <h2>Month-end close <span class="muted">${h(c.periodLabel)}</span></h2>
        <div class="close-state ${c.readyToClose ? "ready" : "blocked"}">${c.readyToClose ? "Closed and signed off."
            : `${c.blocking} item${c.blocking === 1 ? "" : "s"} block the close.`}</div>
        ${c.items.map(i => `<div class="check">
            <div class="icon ${i.done ? "done" : "todo"}">${i.done ? "✓" : "!"}</div>
            <div><strong>${h(i.title)}</strong>${i.detail ? `<div class="small muted">${h(i.detail)}</div>` : ""}
                ${!i.done && i.blockers.length ? `<ul class="mini-list">${i.blockers.slice(0, 8).map(b => `<li>${b.caseId
                    ? `<a href="#case/${b.caseId}">${h(b.label)}</a>` : h(b.label)}</li>`).join("")}${i.blockers.length > 8
                    ? `<li class="muted">+${i.blockers.length - 8} more</li>` : ""}</ul>` : ""}
                ${i.key === "FINAL_REVIEW" && !i.done && !othersBlocking ? `<div class="form-row"><label class="field">Signed by<input id="close-by" value="${h(i.owner)}" maxlength="100"></label>
                    <label class="field">Note<input id="close-note" maxlength="1000" style="min-width:260px"></label>
                    <button class="primary" id="close-sign">Sign off</button></div>` : ""}
                ${i.signedAt ? `<div class="small muted">${dateTime(i.signedAt)}</div>` : ""}
            </div>
            <div class="small">Owner<br><strong>${h(i.owner)}</strong></div>
        </div>`).join("")}
    </section>`;
}

function wire(root, c) {
    root.querySelector("#close-sign")?.addEventListener("click", async () => {
        try {
            await api(`/api/close/${c.period}/signoff`, { method: "POST", body: {
                by: root.querySelector("#close-by").value, note: root.querySelector("#close-note").value || null } });
            banner(`${c.periodLabel} signed off.`, "info");
            showClose(c.period);
        } catch (e) {
            banner(e.message);
        }
    });
}
