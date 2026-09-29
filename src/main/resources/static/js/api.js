// Transport for every API call: a timeout on each request, clean error messages, and the hooks used to
// record a real run and play it back offline ("Offline replay of a recorded run").

const DEFAULT_TIMEOUT_MS = 30000;

export class ApiError extends Error {
    constructor(message, status) {
        super(message);
        this.status = status;
    }
}

const recorder = { on: false, entries: {} };
let replay = null; // { entries: { "GET /api/...": body } }

export function startRecording() {
    recorder.on = true;
    recorder.entries = {};
}

export function stopRecording() {
    recorder.on = false;
    return recorder.entries;
}

export function isRecording() {
    return recorder.on;
}

export function useReplay(entries) {
    replay = entries ? { entries } : null;
}

export function inReplay() {
    return replay !== null;
}

function key(method, path) {
    return method + " " + path;
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
    if (recorder.on) {
        recorder.entries[key(method, path)] = data;
    }
    return data;
}

function fromReplay(method, path) {
    const exact = replay.entries[key(method, path)];
    if (exact !== undefined) {
        return Promise.resolve(structuredClone(exact));
    }
    // polling endpoints: fall back to the same path without query string
    const bare = key(method, path.split("?")[0]);
    const match = Object.keys(replay.entries).find(k => k.split("?")[0] === bare);
    if (match) {
        return Promise.resolve(structuredClone(replay.entries[match]));
    }
    return Promise.reject(new ApiError("This view was not part of the recorded run.", 404));
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
        await new Promise(res => setTimeout(res, everyMs));
    }
}
