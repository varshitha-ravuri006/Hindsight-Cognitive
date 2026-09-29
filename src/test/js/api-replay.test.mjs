// Frontend transport tests (run: node --test src/test/js). Record and replay keep responses in order per endpoint.
import { test } from "node:test";
import assert from "node:assert/strict";
import { api, pace, startRecording, stopRecording, useReplay } from "../../main/resources/static/js/api.js";

test("replay serves each endpoint's responses in order and then holds the last one", async () => {
    useReplay({ "GET /api/advice-runs/1": [{ status: "RUNNING" }, { status: "DONE" }] });
    assert.equal((await api("/api/advice-runs/1")).status, "RUNNING");
    assert.equal((await api("/api/advice-runs/1")).status, "DONE");
    assert.equal((await api("/api/advice-runs/1")).status, "DONE");
    useReplay(null);
});

test("replay matches an endpoint recorded with a different query string and refuses unknown screens", async () => {
    useReplay({ "GET /api/memory/settle?since=2026-09-29T10:00:00Z": [{ stage: "SETTLED" }] });
    assert.equal((await api("/api/memory/settle?since=2026-10-01T00:00:00Z")).stage, "SETTLED");
    await assert.rejects(api("/api/vendors/X"), /not part of the recorded run/);
    assert.equal(pace(3000), 250);
    useReplay(null);
    assert.equal(pace(3000), 3000);
});

test("recording keeps every successful response in order, and never the snapshot itself", async () => {
    let n = 0;
    globalThis.fetch = async () => ({ ok: true, status: 200, text: async () => JSON.stringify({ n: ++n }) });
    startRecording();
    await api("/api/status");
    await api("/api/status");
    await api("/api/snapshot/info");
    const entries = stopRecording();
    assert.deepEqual(entries["GET /api/status"], [{ n: 1 }, { n: 2 }]);
    assert.equal(entries["GET /api/snapshot/info"], undefined);
});

test("server errors surface as clean messages", async () => {
    globalThis.fetch = async () => ({ ok: false, status: 409, text: async () => JSON.stringify({ message: "Load the history first (Step 1)." }) });
    await assert.rejects(api("/api/periods/2026-08/reconcile", { method: "POST" }), /Load the history first/);
});
