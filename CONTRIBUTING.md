# Contributing to Vishwas

Vishwas is one Spring Boot application with strictly separated modules, so several people can work in
parallel without stepping on each other. This page is the contract: which module owns what, which way
dependencies may point, and how to add things safely.

## Ground rules

- **Java 21, Spring Boot 3.3, Maven.** `mvn test` must pass before every commit.
- **The database schema changes only through Flyway** (`src/main/resources/db/migration/V<n>__<what>.sql`).
  Never edit a migration that has been committed; add a new one. SQL must run on both H2 and PostgreSQL.
- **Deterministic logic stays pure.** The normaliser, matcher, outcome detector, category policy,
  confidence and money calculations take plain inputs and return plain outputs: no Spring, no I/O,
  no clock (time is passed in). Each has a focused unit test.
- **External calls never crash a request.** Hindsight and Groq are wrapped in clients with timeouts;
  callers catch failures and degrade (textbook rules, a clear banner), never a 500.
- **No secrets in code or git.** Keys live in `.env` (git-ignored) or the environment.
- **Domain honesty.** Keep potential exposure, confirmed loss and recovered amounts separate. Never call
  an open discrepancy "money lost". Recommendations are informational, never tax advice, and never
  recommend a payment action.

## Module map (`src/main/java/com/vishwas`)

| Package      | Owns                                                                                     | May depend on |
|--------------|------------------------------------------------------------------------------------------|---------------|
| `ingest`     | Vendor master, GSTIN validation, invoice-number normaliser (applied at import), purchase-register CSV and simplified GSTR-2B JSON parsing, stored invoice rows per period | `config` |
| `matching`   | Deterministic matcher (exact + fuzzy candidates, all mismatch types), `Mismatch` entity, reconciliation run | `ingest` |
| `outcomes`   | Outcome detector (judges open mismatches when a later GSTR-2B arrives), money calculations (exposure vs confirmed loss vs recovered) | `matching`, `ingest` |
| `memory`     | Everything Hindsight: the REST client (`memory.hindsight`), bank design (missions, directives, mental models), memory writer, history loader, vendor beliefs and observation history, curation, knowledge pages | `ingest`, `matching`, `outcomes` (read-only) |
| `advisor`    | Category policy (four decision categories), confidence, vendor dimension profiles, memory advisor (parallel reflect), textbook fallback, no-memory baseline | `matching`, `outcomes`, `memory`, `llm` |
| `workflow`   | Action center (case states, owner, due date, notes, reminders, vendor e-mail drafts), learning loop (decisions + accuracy), auto-resolve audit, month-end close checklist | `matching`, `outcomes`, `advisor`, `memory`, `llm` |
| `assistant`  | Natural-language assistant: tool definitions, tool execution over structured DB queries and Hindsight recall, routing fallback | `workflow`, `advisor`, `outcomes`, `matching`, `memory`, `llm` |
| `api`        | REST controllers, request/response DTOs, JSON error handling. Thin: no business logic | everything above |
| `web`        | Static frontend (`src/main/resources/static`: vanilla JS + marked) and web-only endpoints | `api` |
| `config`     | `VishwasProperties` (all tunables), beans, `DATABASE_URL` translation | - |
| `llm`        | Groq client (JSON validation, one retry, graceful fallback; function calling) | `config` |
| `demo`       | Seed data loading, one-click reset, snapshot store for the offline replay | anything (demo glue only) |

Dependencies point **down the table only** (`api` → `assistant` → `workflow` → `advisor` → `outcomes` →
`matching` → `ingest`). If you need something "upward", publish a small interface in the lower module
and implement it above, or move the shared type down.

## Where things live

- Seed data: `src/main/resources/seed/` (purchase registers, simplified GSTR-2B JSON, journal of past
  communications and decisions, vendor letters as PDF). The simplified GSTR-2B format is documented in
  `docs/gstr2b-simplified.md`; it is **not** the GST portal schema. The invoice files and letters are
  generated deterministically by `src/test/java/com/vishwas/tools/SeedGenerator.java` (the story of each
  vendor is written out there); `journal.json` is hand-written. Regenerate with
  `mvn -q test-compile exec:java -Dexec.mainClass=com.vishwas.tools.SeedGenerator -Dexec.classpathScope=test`
  and keep `SeedScenarioTest` green: it replays the story and asserts every vendor behaves as designed.
- Memory design (missions, directives, mental models): `memory/MemoryDesign.java`. Tune wording there.
- Tunables (tolerances, at-risk months, materiality, approved auto-resolve rules): `application.yml`
  under `vishwas.*`, mirrored in `config/VishwasProperties.java`.

## Frontend (`src/main/resources/static`)

No build step: ES modules loaded by `index.html`, `marked` from jsDelivr, styles in `css/app.css` (light and dark
through CSS variables). One module per surface:

| Module | Surface |
|---|---|
| `js/api.js` | every request (timeouts, JSON errors), recording and offline replay |
| `js/steps.js` | Steps 1 to 3 (history, reconcile, Money at risk) |
| `js/nextmonth.js` | Step 4 (next GSTR-2B, live consolidation indicator) |
| `js/brief.js`, `js/workbench.js` | investigation brief and "Work this case" |
| `js/vendor.js`, `js/curation.js` | vendor profile, knowledge page, letters, "Correct this history" |
| `js/actions.js`, `js/assistant.js`, `js/close.js` | Action center, Assistant, Month-end close tabs |
| `js/drawer.js` | "What Vishwas remembered" drawer |

The Reconcile tab (Steps 1 to 4) is the demo path: keep it calm and unchanged; new features go into the brief, the
vendor profile, the drawer or a tab. Frontend tests: `node --test src/test/js/api-replay.test.mjs` (`npm run test:js`).

## Scripts and demo tooling

- `scripts/prepare-demo` / `scripts/prepare-demo.ps1`: reset the demo bank, load history, wait for consolidation,
  print READY (see the README's demo-day checklist).
- ⋯ menu: **Record this run** / **Stop and save recording** writes `data/snapshot-<bank>.json`;
  **Play offline replay** serves the whole UI from it. `GET /api/snapshot/info` says whether one exists.

## Working on a module

1. Write or extend the pure logic first, with a unit test next to it (`src/test/java/...` mirrors main).
2. Wire persistence (entity + repository + Flyway migration) if needed.
3. Expose it through `api` last, and add the UI in `static/`.
4. External APIs in tests: use `support/StubApis` (an in-process fake of Hindsight and Groq that records
   every request). Tests must never call the real services; `src/test/resources/application.properties`
   blanks the keys.

## Commits

Small, focused commits with a clear subject line (`matching: detect TAX_HEAD_MISMATCH`), body explaining
why. One phase or feature per commit.
