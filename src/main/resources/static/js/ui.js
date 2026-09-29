// Small UI primitives: banners, the side panel, confirmation dialog.
import { h } from "./fmt.js";

const $ = sel => document.querySelector(sel);

export function banner(message, kind = "error", { timeoutMs } = {}) {
    const box = $("#banners");
    const el = document.createElement("div");
    el.className = "banner " + (kind === "error" ? "" : kind);
    el.setAttribute("role", kind === "error" ? "alert" : "status");
    el.innerHTML = `<span>${h(message)}</span><button aria-label="Dismiss">✕</button>`;
    el.querySelector("button").onclick = () => el.remove();
    box.appendChild(el);
    const ttl = timeoutMs ?? (kind === "error" ? 14000 : 7000);
    if (ttl > 0) setTimeout(() => el.remove(), ttl);
    return el;
}

export function clearBanners() {
    $("#banners").innerHTML = "";
}

let onPanelClose = null;

export function openPanel(html, onClose) {
    $("#panel-body").innerHTML = html;
    $("#panel").hidden = false;
    $("#scrim").hidden = false;
    $("#panel").scrollTop = 0;
    onPanelClose = onClose || null;
    document.body.style.overflow = "hidden";
    $("#panel-close").focus();
}

export function panelBody() {
    return $("#panel-body");
}

export function closePanel() {
    $("#panel").hidden = true;
    $("#scrim").hidden = true;
    document.body.style.overflow = "";
    const cb = onPanelClose;
    onPanelClose = null;
    cb?.();
}

export function initPanel() {
    $("#panel-close").onclick = closePanel;
    $("#scrim").onclick = closePanel;
    document.addEventListener("keydown", e => {
        if (e.key === "Escape" && !$("#panel").hidden) closePanel();
    });
}

export function confirmDialog(title, text, okLabel = "Confirm") {
    const d = $("#confirm-dialog");
    $("#confirm-title").textContent = title;
    $("#confirm-text").textContent = text;
    $("#confirm-ok").textContent = okLabel;
    return new Promise(resolve => {
        d.onclose = () => resolve(d.returnValue === "ok");
        d.showModal();
    });
}

export function skeletonLines(n = 3) {
    return Array.from({ length: n }, (_, i) => `<div class="skeleton sk-line" style="width:${90 - i * 18}%"></div>`).join("");
}
