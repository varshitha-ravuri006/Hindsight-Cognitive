// Assistant: every answer comes from tool calls (structured database queries and Hindsight recall), each question
// is stateless, and the records the answer used are listed underneath.
import { api } from "./api.js";
import { h, markdown } from "./fmt.js";
import { skeletonLines } from "./ui.js";

const EXAMPLES = [
    "Which vendors had repeated invoice-number mismatches this quarter?",
    "Show unresolved discrepancies above ₹25,000",
    "What happened to the Kaveri mismatch last month?",
    "Draft follow-up emails for vendors with missing documents",
];

export function showAssistant() {
    const root = document.getElementById("view-assistant");
    if (root.dataset.ready) return;
    root.dataset.ready = "1";
    root.innerHTML = `<section class="step active" style="padding:18px 20px">
        <h2>Assistant</h2>
        <p class="note">Answers only from Vishwas's records: database queries and Hindsight recall, never from the conversation.
            Each question stands alone, and every answer lists the records it used.</p>
        <form class="ask-row" id="ask-form"><input id="ask-input" maxlength="500" placeholder="Ask about vendors, cases, amounts or what happened"
            aria-label="Question"><button class="primary">Ask</button></form>
        <div class="examples">${EXAMPLES.map(e => `<button type="button" data-q="${h(e)}">${h(e)}</button>`).join("")}</div>
        <div id="answers"></div>
        <p class="disclaimer">Informational only, not tax advice.</p>
    </section>`;
    root.querySelector("#ask-form").addEventListener("submit", e => {
        e.preventDefault();
        ask(root.querySelector("#ask-input").value);
    });
    root.querySelectorAll("[data-q]").forEach(b => b.addEventListener("click", () => {
        root.querySelector("#ask-input").value = b.dataset.q;
        ask(b.dataset.q);
    }));
}

async function ask(question) {
    if (!question || !question.trim()) return;
    const box = document.getElementById("answers");
    const card = document.createElement("section");
    card.className = "step active";
    card.style.padding = "14px 18px";
    card.innerHTML = `<p><strong>${h(question)}</strong></p>${skeletonLines(3)}`;
    box.prepend(card);
    try {
        const a = await api("/api/assistant/ask", { method: "POST", body: { question }, timeoutMs: 90000 });
        card.innerHTML = `<p><strong>${h(a.question)}</strong> <span class="muted small">· ${(a.ms / 1000).toFixed(1)} s ·
            ${a.mode === "MODEL_WITH_TOOLS" ? "answered by Groq from tool results" : "answered directly from the records"}</span></p>
            <div class="markdown">${markdown(a.answer)}</div>
            ${records(a.records)}
            <details><summary class="small">How it answered (${a.toolCalls.length} tool call${a.toolCalls.length === 1 ? "" : "s"})</summary>
                <ul class="mini-list">${a.toolCalls.map(t => `<li><span class="mono">${h(t.tool)}</span> ${h(JSON.stringify(t.args))}
                    <span class="muted small">· ${t.records} record(s) · ${t.ms} ms</span>${t.error ? `<br><span class="note">${h(t.error)}</span>` : ""}</li>`).join("")}</ul>
            </details>`;
    } catch (e) {
        card.innerHTML = `<p><strong>${h(question)}</strong></p><div class="banner">${h(e.message)}</div>`;
    }
}

function records(refs) {
    if (!refs.length) return `<p class="note">No records were used.</p>`;
    const link = r => r.kind === "case" ? `<a href="#case/${encodeURIComponent(r.id)}">${h(r.label)}</a>`
        : r.kind === "vendor" ? `<a href="#vendor/${encodeURIComponent(r.id)}">${h(r.label)}</a>` : h(r.label);
    const groups = { case: "Cases", vendor: "Vendors", communication: "Vendor messages", memory: "Memories" };
    return `<details class="records" open><summary class="small">Records used (${refs.length})</summary>
        ${Object.entries(groups).map(([k, label]) => {
            const list = refs.filter(r => r.kind === k);
            return list.length ? `<p class="small" style="margin:6px 0 2px"><strong>${label}</strong></p>
                <ul class="mini-list">${list.slice(0, 20).map(r => `<li>${link(r)}</li>`).join("")}${list.length > 20 ? `<li class="muted">+${list.length - 20} more</li>` : ""}</ul>` : "";
        }).join("")}</details>`;
}
