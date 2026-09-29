// "Work this case", inside the investigation brief: state, owner and due date, notes, reminders, a vendor
// e-mail draft, recording what was sent or received, the ITC write-off and reversing an auto-resolution.
import { api } from "./api.js";
import { dateTime, day, h } from "./fmt.js";
import { banner } from "./ui.js";

const LABEL = {
    DETECTED: "Detected", INVESTIGATING: "Investigating", WAITING_FOR_VENDOR: "Waiting for vendor",
    VENDOR_RESPONDED: "Vendor responded", ACCOUNTANT_REVIEW: "Accountant review", RESOLVED: "Resolved", ESCALATED: "Escalated",
};

export async function loadWorkbench(container, caseId, row) {
    container.innerHTML = `<h3>Work this case</h3><p class="muted small">Loading…</p>`;
    try {
        const d = await api(`/api/cases/${caseId}/workflow`);
        container.innerHTML = render(d, row);
        wire(container, caseId, row, d);
    } catch (e) {
        container.innerHTML = `<h3>Work this case</h3><p class="note">${h(e.message)}</p>`;
    }
}

function render(d, row) {
    const open = row.status === "OPEN" || row.status === "AT_RISK";
    const autoResolved = d.history.some(a => a.action === "AUTO_RESOLVE_APPLIED") && !d.history.some(a => a.action === "AUTO_RESOLVE_REVERSED")
        && row.status === "RESOLVED";
    return `<h3>Work this case <span class="state-pill ${h(d.state)}">${h(d.stateLabel)}</span></h3>
    ${open ? `<div class="form-row">${d.allowedNext.map(s => `<button class="secondary" data-move="${s}">Move to ${h(LABEL[s])}</button>`).join("")}</div>` : ""}
    <div class="form-row">
        <label class="field">Owner<input id="wb-owner" value="${h(d.owner || "")}" placeholder="Lakshmi Prasad" maxlength="100"></label>
        <label class="field">Due<input id="wb-due" type="date" value="${h(d.dueDate || "")}"></label>
        <button class="secondary" id="wb-assign">Save</button>
    </div>

    <details ${d.notes.length ? "open" : ""}><summary>Notes (${d.notes.length})</summary>
        <ul class="mini-list">${d.notes.map(n => `<li><span class="muted small">${dateTime(n.createdAt)} · ${h(n.author)}</span><br>${h(n.text)}</li>`).join("")}</ul>
        <div class="form-row"><textarea id="wb-note" placeholder="Add a note" maxlength="2000"></textarea><button class="secondary" id="wb-add-note">Add note</button></div>
    </details>

    <details ${d.reminders.some(r => !r.done) ? "open" : ""}><summary>Reminders (${d.reminders.filter(r => !r.done).length} open)</summary>
        <ul class="mini-list">${d.reminders.map(r => `<li>${r.done ? "<s>" : ""}${day(r.dueOn)} · ${h(r.text)}${r.done ? "</s>" : ` <span class="link" data-done="${r.id}">done</span>`}</li>`).join("")}</ul>
        <div class="form-row"><label class="field">On<input id="wb-rem-date" type="date"></label>
            <label class="field">What<input id="wb-rem-text" placeholder="Follow up with the vendor" maxlength="500"></label>
            <button class="secondary" id="wb-add-rem">Add reminder</button></div>
    </details>

    ${open ? `<details><summary>Vendor e-mail and replies</summary>
        <div class="form-row"><button class="secondary" id="wb-draft">Draft follow-up e-mail</button></div>
        <div id="wb-email" class="email-box"></div>
        <div class="form-row">
            <label class="field">Reply received<input id="wb-reply" placeholder="What the vendor said" maxlength="1000" style="min-width:320px"></label>
            <label class="field">Promised by<input id="wb-promise" type="date"></label>
            <button class="secondary" id="wb-record-reply">Record reply</button>
        </div>
    </details>
    <details><summary>Reverse the ITC (confirmed loss)</summary>
        <p class="note">Only when the credit will not be claimed. This turns the open exposure into a confirmed loss and is audited.</p>
        <div class="form-row"><label class="field">Approved by<input id="wb-approver" placeholder="Srinivas Reddy" maxlength="100"></label>
            <label class="field">Note<input id="wb-wo-note" maxlength="1000" style="min-width:260px"></label>
            <button class="danger" id="wb-writeoff">Write off ITC</button></div>
    </details>` : ""}
    ${autoResolved ? `<div class="form-row"><span class="note">Auto-resolved under an approved rule.</span>
        <button class="secondary" id="wb-reverse">Reverse auto-resolution</button></div>` : ""}

    ${d.history.length ? `<details><summary>Audit trail (${d.history.length})</summary><ul class="mini-list">${d.history.map(a =>
        `<li><span class="muted small">${dateTime(a.occurredAt)} · ${h(a.actor)} · ${h(a.action.replaceAll("_", " ").toLowerCase())}</span><br>${h(a.note || "")}${a.approvedBy ? ` <em>(approved by ${h(a.approvedBy)})</em>` : ""}</li>`).join("")}</ul></details>` : ""}`;
}

function wire(c, id, row, d) {
    const run = async (fn, ok) => {
        try {
            await fn();
            if (ok) banner(ok, "info");
            document.dispatchEvent(new CustomEvent("vishwas:changed"));
            loadWorkbench(c, id, row);
        } catch (e) {
            banner(e.message);
        }
    };
    const post = (path, body) => api(path, { method: "POST", body });
    c.querySelectorAll("[data-move]").forEach(b => b.addEventListener("click", () =>
        run(() => post(`/api/cases/${id}/transition`, { to: b.dataset.move }), `Moved to ${LABEL[b.dataset.move]}.`)));
    c.querySelector("#wb-assign").addEventListener("click", () => run(() => post(`/api/cases/${id}/assign`, {
        owner: c.querySelector("#wb-owner").value || null, dueDate: c.querySelector("#wb-due").value || null }), "Saved."));
    c.querySelector("#wb-add-note").addEventListener("click", () => {
        const text = c.querySelector("#wb-note").value.trim();
        if (text) run(() => post(`/api/cases/${id}/notes`, { text }));
    });
    c.querySelector("#wb-add-rem").addEventListener("click", () => {
        const dueOn = c.querySelector("#wb-rem-date").value;
        if (!dueOn) return banner("Pick a date for the reminder.", "warn");
        run(() => post(`/api/cases/${id}/reminders`, { dueOn, text: c.querySelector("#wb-rem-text").value || null }));
    });
    c.querySelectorAll("[data-done]").forEach(s => s.addEventListener("click", () => run(() => post(`/api/reminders/${s.dataset.done}/done`))));
    c.querySelector("#wb-draft")?.addEventListener("click", async () => {
        const box = c.querySelector("#wb-email");
        box.innerHTML = `<p class="muted small">Drafting…</p>`;
        try {
            const dr = await api(`/api/cases/${id}/email-draft`, { timeoutMs: 60000 });
            box.innerHTML = `<p class="small"><strong>To:</strong> ${h(dr.to || "")} · <strong>Subject:</strong> <input id="wb-subject" value="${h(dr.subject)}" style="min-width:360px"></p>
                <textarea id="wb-body">${h(dr.body)}</textarea>
                <p class="note">${dr.source === "GROQ" ? "Drafted by Groq and checked" : "Standard template"} · Vishwas never sends e-mail; copy it into your mail client.</p>
                <div class="form-row"><button class="secondary" id="wb-copy">Copy</button><button class="primary" id="wb-sent">Mark as sent</button></div>`;
            box.querySelector("#wb-copy").onclick = () => navigator.clipboard?.writeText(box.querySelector("#wb-body").value)
                .then(() => banner("Copied.", "info")).catch(() => banner("Copy failed; select the text instead.", "warn"));
            box.querySelector("#wb-sent").onclick = () => run(() => post(`/api/cases/${id}/messages`, {
                direction: "OUT", channel: "EMAIL", summary: "Sent follow-up e-mail: " + box.querySelector("#wb-subject").value }),
                "Recorded. The case now waits for the vendor, with a reminder in 7 days.");
        } catch (e) {
            box.innerHTML = `<p class="note">${h(e.message)}</p>`;
        }
    });
    c.querySelector("#wb-record-reply")?.addEventListener("click", () => {
        const summary = c.querySelector("#wb-reply").value.trim();
        if (!summary) return banner("Summarise the reply.", "warn");
        run(() => post(`/api/cases/${id}/messages`, { direction: "IN", channel: "EMAIL", summary,
            promiseBy: c.querySelector("#wb-promise").value || null }), "Reply recorded. A promised date will be checked against the next GSTR-2B.");
    });
    c.querySelector("#wb-writeoff")?.addEventListener("click", () => {
        const approvedBy = c.querySelector("#wb-approver").value.trim();
        if (!approvedBy) return banner("An ITC write-off needs the approver's name.", "warn");
        run(() => post(`/api/cases/${id}/write-off`, { approvedBy, note: c.querySelector("#wb-wo-note").value || null }),
            "ITC reversed: recorded as a confirmed loss.");
    });
    c.querySelector("#wb-reverse")?.addEventListener("click", () =>
        run(() => post(`/api/cases/${id}/auto-resolve/reverse`, { reason: "Reversed from the brief" }), "Auto-resolution reversed; the case is open again."));
}
