// "What Vishwas remembered": the recalled facts behind the advice, current observations per vendor, the
// mental models, and the monthly memory batches. Collapsed by default; loaded when opened.
import { api } from "./api.js";
import { dateTime, DIMENSION_LABEL, h, markdown } from "./fmt.js";
import { skeletonLines } from "./ui.js";

let loadedFor = null;

export function initDrawer(getPeriod) {
    const drawer = document.getElementById("memory-drawer");
    drawer.addEventListener("toggle", () => {
        if (drawer.open && loadedFor !== getPeriod()) load(getPeriod());
    });
}

export function invalidateDrawer() {
    loadedFor = null;
    const drawer = document.getElementById("memory-drawer");
    if (drawer.open) drawer.dispatchEvent(new Event("toggle"));
}

async function load(period) {
    const body = document.getElementById("drawer-body");
    body.innerHTML = skeletonLines(5);
    try {
        const d = await api(`/api/memory/drawer?period=${period}`, { timeoutMs: 60000 });
        loadedFor = period;
        body.innerHTML = `
            <div class="tabs" role="tablist">
                <button class="on" data-tab="facts">Recalled facts (${d.facts.length})</button>
                <button data-tab="obs">Observations</button>
                <button data-tab="models">Mental models</button>
                <button data-tab="batches">Memory batches</button>
            </div>
            <div data-pane="facts">${facts(d.facts)}</div>
            <div data-pane="obs" hidden>${d.available ? observations(d.observations || []) : off()}</div>
            <div data-pane="models" hidden>${d.available ? models(d.mentalModels || []) : off()}</div>
            <div data-pane="batches" hidden>${batches(d.batches || [])}</div>`;
        body.querySelectorAll("[data-tab]").forEach(b => b.addEventListener("click", () => {
            body.querySelectorAll("[data-tab]").forEach(x => x.classList.toggle("on", x === b));
            body.querySelectorAll("[data-pane]").forEach(p => p.hidden = p.dataset.pane !== b.dataset.tab);
        }));
    } catch (e) {
        body.innerHTML = `<p class="note">Could not load memory: ${h(e.message)}</p>`;
    }
}

function off() {
    return `<p class="note">Memory is off: Hindsight is not configured or not reachable.</p>`;
}

function facts(list) {
    if (!list.length) return `<p class="note">No recalled facts yet. They appear once advice has been generated from memory.</p>`;
    return `<ul class="facts">${list.map(f => `<li><span class="ctx">${h(f.vendor || "")} · ${h(f.context || f.type || "")}</span>${h(f.text)}</li>`).join("")}</ul>`;
}

function observations(list) {
    if (!list.length) return `<p class="note">No vendors with open cases.</p>`;
    return list.map(v => `<h4 style="margin:10px 0 4px">${h(v.vendor || "")}</h4>${v.error ? `<p class="note">${h(v.error)}</p>`
        : `<ul class="facts">${(v.beliefs || []).map(b => `<li><span class="ctx">${h(DIMENSION_LABEL[b.dimension] || "vendor")}</span>${h(b.text)}</li>`).join("") || "<li class='muted'>No beliefs yet.</li>"}</ul>`}`).join("");
}

function models(list) {
    return list.map(m => `<section style="margin:8px 0"><h3>${h(m.name)}</h3>
        <p class="note">${m.lastRefreshedAt ? "Refreshed " + dateTime(m.lastRefreshedAt) : "Not refreshed yet"}${m.stale ? " · newer memories since" : ""}${m.error ? " · " + h(m.error) : ""}</p>
        <div class="markdown">${m.content ? markdown(m.content) : "<p class='muted'>No content yet.</p>"}</div></section>`).join("");
}

function batches(list) {
    if (!list.length) return `<p class="note">Nothing sent to memory yet.</p>`;
    return `<table class="grid"><thead><tr><th>Batch</th><th class="num">Items</th><th>Status</th><th>Started</th></tr></thead><tbody>
        ${list.map(b => `<tr><td>${h(b.label)}</td><td class="num">${b.items}</td><td>${h(b.status)}</td><td>${dateTime(b.startedAt)}</td></tr>`).join("")}
        </tbody></table>`;
}
