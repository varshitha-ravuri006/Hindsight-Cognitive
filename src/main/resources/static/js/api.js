// Transport for every API call: a timeout on each request, clean error messages, and the hooks used to
// record a real run and play it back offline ("Offline replay of a recorded run").

const DEFAULT_TIMEOUT_MS = 30000;

export class ApiError extends Error {
    constructor(message, status) {
        super(message);
        this.status = status;
    }
}

// Recording keeps every response per endpoint IN ORDER, so a replay also reproduces progress (an advice run going
// from RUNNING to DONE) and before/after reads (a belief before and after consolidation).
const RECORD_KEY = "vishwas-recording";
const recorder = { on: false, entries: {} };
let replay = null; // { entries: { "GET /api/...": [body, body, ...] }, cursor: { key: index } }

try {
    const saved = sessionStorage.getItem(RECORD_KEY);
    if (saved) Object.assign(recorder, JSON.parse(saved), { on: true });
} catch { /* storage unavailable */ }

function persistRecording() {
    try { sessionStorage.setItem(RECORD_KEY, JSON.stringify({ entries: recorder.entries })); } catch { /* too large or unavailable */ }
}

export function startRecording() {
    recorder.on = true;
    recorder.entries = {};
    persistRecording();
}

export function stopRecording() {
    recorder.on = false;
    try { sessionStorage.removeItem(RECORD_KEY); } catch { /* ignore */ }
    return recorder.entries;
}

export function isRecording() {
    return recorder.on;
}

export function useReplay(entries) {
    replay = entries ? { entries, cursor: {} } : null;
}

export function inReplay() {
    return replay !== null;
}

/** Waits between polls: real time normally, compressed during an offline replay. */
export function pace(ms) {
    return replay ? Math.min(ms, 250) : ms;
}

function key(method, path) {
    return method + " " + path;
}

function record(method, path, data) {
    if (!recorder.on || path.startsWith("/api/snapshot") || path.startsWith("/api/health")) return;
    const k = key(method, path);
    (recorder.entries[k] ||= []).push(data);
    if (recorder.entries[k].length > 400) recorder.entries[k].shift();
    persistRecording();
}

export async function api(path, { method = "GET", body, form, timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
    if (replay) {
        return fromReplay(method, path);
    }
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    const init = { method, signal: ctrl.signal, headers: { Accept: "application/json" } };
    if (form) {
        init.body = form;
    } else if (body !== undefined) {
        init.body = JSON.stringify(body);
        init.headers["Content-Type"] = "application/json";
    }
    let res;
    try {
        res = await fetch(path, init);
    } catch (e) {
        throw new ApiError(e.name === "AbortError"
            ? `The server did not answer within ${Math.round(timeoutMs / 1000)} s (${method} ${path}).`
            : "The server could not be reached. Is Vishwas running?", 0);
    } finally {
        clearTimeout(timer);
    }
    const text = await res.text();
    let data = null;
    try {
        data = text ? JSON.parse(text) : null;
    } catch {
        data = { message: text };
    }
    if (!res.ok) {
        throw new ApiError((data && (data.message || data.error)) || `HTTP ${res.status}`, res.status);
    }
    record(method, path, data);
    return data;
}

function fromReplay(method, path) {
    let k = key(method, path);
    if (replay.entries[k] === undefined) {
        // same endpoint with a different query string (e.g. a timestamp): use the recorded one
        k = Object.keys(replay.entries).find(x => x.split("?")[0] === k.split("?")[0]);
    }
    const seq = k ? replay.entries[k] : undefined;
    if (!seq || !seq.length) {
        return Promise.reject(new ApiError("This view was not part of the recorded run.", 404));
    }
    const i = replay.cursor[k] || 0;
    replay.cursor[k] = Math.min(i + 1, seq.length - 1);
    return new Promise(res => setTimeout(() => res(structuredClone(seq[i])), 120));
}

/** Poll until done(result) is true, with an overall deadline; never spins forever. */
export async function poll(path, done, { everyMs = 800, deadlineMs = 180000, onTick } = {}) {
    const until = Date.now() + deadlineMs;
    for (;;) {
        const r = await api(path);
        onTick?.(r);
        if (done(r)) {
            return r;
        }
        if (Date.now() > until) {
            throw new ApiError("This is taking longer than expected. It will keep running on the server; refresh to check.", 0);
        }
        await new Promise(res => setTimeout(res, pace(everyMs)));
    }
}
