// Vendor-profile memory work: the Hindsight knowledge page (with dossier export), uploading a vendor letter
// (file retain), and "Correct this history" (edit or invalidate a fact, audited, beliefs rebuilt).
import { api } from "./api.js";
import { dateTime, h, markdown } from "./fmt.js";
import { banner, panelBody } from "./ui.js";

export function initVendorMemory(p) {
    loadPage(p);
    loadFacts(p);
    const root = panelBody();
    root.querySelector("#page-refresh")?.addEventListener("click", async () => {
        try {
            await api(`/api/vendors/${encodeURIComponent(p.gstin)}/page/refresh`, { method: "POST" });
            banner("Hindsight is rewriting the page from current memory; open the profile again in a minute.", "info");
        } catch (e) {
            banner(e.message);
        }
    });
    root.querySelector("#letter-form")?.addEventListener("submit", async ev => {
        ev.preventDefault();
        const form = new FormData(ev.target);
        for (const k of ["receivedOn", "promiseBy", "invoices"]) if (!form.get(k)) form.delete(k);
        try {
            const r = await api(`/api/vendors/${encodeURIComponent(p.gstin)}/letters`, { method: "POST", form, timeoutMs: 60000 });
            banner(r.sentToMemory ? "Letter stored and sent to memory." : "Letter stored. Memory is off, so it was not sent to Hindsight.", "info");
            ev.target.reset();
            const { openVendor } = await import("./vendor.js");
            openVendor(p.gstin);
        } catch (e) {
            banner(e.message);
        }
    });
}

async function loadPage(p) {
    const el = panelBody().querySelector("#knowledge-page");
    if (!el) return;
    try {
        const r = await api(`/api/vendors/${encodeURIComponent(p.gstin)}/page`, { timeoutMs: 30000 });
        if (!el.isConnected) return;
        el.innerHTML = r.available ? `<p class="note">${h(r.name)}${r.refreshedAt ? " · refreshed " + dateTime(r.refreshedAt) : ""}</p>
            <div class="markdown">${markdown(r.body)}</div>` : `<p class="note">${h(r.message)}</p>`;
    } catch (e) {
        if (el.isConnected) el.innerHTML = `<p class="note">${h(e.message)}</p>`;
    }
}

async function loadFacts(p) {
    const el = panelBody().querySelector("#curation");
    if (!el) return;
    try {
        const r = await api(`/api/vendors/${encodeURIComponent(p.gstin)}/memory-facts`, { timeoutMs: 30000 });
        if (!el.isConnected) return;
        const corrections = r.corrections || [];
        el.innerHTML = `
            ${r.available ? `<input id="fact-filter" placeholder="Filter facts (e.g. KPL/0547)" style="width:100%;margin-bottom:6px">
                <div id="fact-list">${(r.facts || []).map(fact).join("") || "<p class='muted'>No facts yet.</p>"}</div>`
                : `<p class="note">${h(r.message)}</p>`}
            ${corrections.length ? `<details style="margin-top:10px"><summary>Corrections made (${corrections.length})</summary><ul class="mini-list">
                ${corrections.map(c => `<li><span class="muted small">${dateTime(c.createdAt)} · ${h(c.actor)} · ${h(c.action.toLowerCase())} · ${h(c.status.toLowerCase())}</span><br>
                    <em>${h(c.reason)}</em><br><s>${h(c.oldText || "")}</s>${c.newText ? `<br>${h(c.newText)}` : ""}${c.error ? `<br><span class="note">${h(c.error)}</span>` : ""}</li>`).join("")}
            </ul></details>` : ""}`;
        el.querySelector("#fact-filter")?.addEventListener("input", e => {
            const q = e.target.value.toLowerCase();
            el.querySelectorAll(".fact-row").forEach(row => row.hidden = q && !row.textContent.toLowerCase().includes(q));
        });
        el.querySelectorAll("[data-correct]").forEach(b => b.addEventListener("click", () => openForm(p, b.closest(".fact-row"), b.dataset.correct)));
    } catch (e) {
        if (el.isConnected) el.innerHTML = `<p class="note">${h(e.message)}</p>`;
    }
}

function fact(f) {
    const invalid = f.state === "invalidated";
    return `<div class="fact-row ${invalid ? "invalidated" : ""}" data-id="${h(f.id)}">
        <div><span class="muted small">${h(f.context || "")}${f.date ? " · " + dateTime(f.date) : ""}${f.editedAt ? " · edited" : ""}</span><br>
            <span class="fact-text">${h(f.text)}</span>${invalid && f.invalidationReason ? `<br><span class="note">Invalidated: ${h(f.invalidationReason)}</span>` : ""}</div>
        <div><button class="secondary" data-correct="${invalid ? "REVERT" : "EDIT"}">${invalid ? "Restore" : "Correct"}</button></div>
    </div>`;
}

function openForm(p, row, mode) {
    if (row.querySelector("form")) return;
    const text = row.querySelector(".fact-text").textContent;
    const form = document.createElement("form");
    form.style.gridColumn = "1 / -1";
    form.innerHTML = mode === "REVERT"
        ? `<div class="form-row"><label class="field">Why restore it?<input name="reason" required maxlength="1000" style="min-width:320px"></label>
            <button class="primary">Restore fact</button></div>`
        : `<div class="form-row"><label class="field">Action<select name="action"><option value="EDIT">Edit the text</option>
                <option value="INVALIDATE">Invalidate (wrong, keep for audit)</option></select></label></div>
           <div class="form-row"><textarea name="newText" maxlength="4000" style="width:100%">${h(text)}</textarea></div>
           <div class="form-row"><label class="field">Why?<input name="reason" required maxlength="1000" style="min-width:320px"
                placeholder="e.g. the invoice appeared in the June GSTR-2B"></label><button class="primary">Save correction</button>
                <button type="button" class="secondary" data-cancel>Cancel</button></div>`;
    row.appendChild(form);
    form.querySelector("[data-cancel]")?.addEventListener("click", () => form.remove());
    form.addEventListener("submit", async ev => {
        ev.preventDefault();
        const data = new FormData(form);
        const action = mode === "REVERT" ? "REVERT" : data.get("action");
        try {
            const r = await api(`/api/vendors/${encodeURIComponent(p.gstin)}/corrections`, { method: "POST", body: {
                memoryId: row.dataset.id, action, reason: data.get("reason"),
                newText: action === "EDIT" ? data.get("newText") : null } });
            banner(r.status === "DONE" ? "Corrected and audited. Hindsight is rebuilding this vendor's beliefs." : "The correction was recorded but failed: " + r.error,
                r.status === "DONE" ? "info" : "warn");
            loadFacts(p);
        } catch (e) {
            banner(e.message);
        }
    });
}
