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
| `ingest`     | Vendor master, GSTIN validation, purchase-register CSV and simplified GSTR-2B JSON parsing, stored invoice rows per period | `config` |
| `matching`   | Invoice-number normaliser, deterministic matcher (exact + fuzzy candidates, all mismatch types), `Mismatch` entity, reconciliation run | `ingest` |
| `outcomes`   | Outcome detector (judges open mismatches when a later GSTR-2B arrives), money calculations (exposure vs confirmed loss vs recovered) | `matching`, `ingest` |
| `memory`     | Everything Hindsight: the REST client (`memory.hindsight`), bank design (missions, directives, mental models), memory writer, history loader, vendor beliefs and observation history, curation, knowledge pages | `ingest`, `matching`, `outcomes` (read-only) |
| `advisor`    | Category policy (four decision categories), confidence, vendor dimension profiles, memory advisor (parallel reflect), textbook fallback, no-memory baseline | `matching`, `outcomes`, `memory`, `llm` |
| `workflow`   | Action center (case states, owner, due date, notes, reminders, vendor e-mail drafts), learning loop (decisions + accuracy), auto-resolve audit, month-end close checklist | `matching`, `outcomes`, `advisor`, `memory`, `llm` |
| `assistant`  | Natural-language assistant: tool definitions, tool execution over structured DB queries and Hindsight recall, routing fallback | `workflow`, `advisor`, `outcomes`, `matching`, `memory`, `llm` |
| `api`        | REST controllers, request/response DTOs, JSON error handling. Thin: no business logic | everything above |
| `web`        | Static frontend (`src/main/resources/static`: vanilla JS + marked) and web-only endpoints | `api` |
| `config`     | `VishwasProperties` (all tunables), beans, `DATABASE_URL` translation | - |
| `llm`        | Groq client (JSON validation, one retry, graceful fallback; function calling) | `config` |
| `demo`       | Seed data loading, one-click reset, snapshot record/replay for offline demos | anything (demo glue only) |

Dependencies point **down the table only** (`api` → `assistant` → `workflow` → `advisor` → `outcomes` →
`matching` → `ingest`). If you need something "upward", publish a small interface in the lower module
and implement it above, or move the shared type down.

## Where things live

- Seed data: `src/main/resources/seed/` (purchase registers, simplified GSTR-2B JSON, journal of past
  communications and decisions, vendor letters as PDF). The simplified GSTR-2B format is documented in
  `docs/gstr2b-simplified.md`; it is **not** the GST portal schema.
- Memory design (missions, directives, mental models): `memory/MemoryDesign.java`. Tune wording there.
- Tunables (tolerances, at-risk months, materiality, approved auto-resolve rules): `application.yml`
  under `vishwas.*`, mirrored in `config/VishwasProperties.java`.

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
