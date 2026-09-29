# Vishwas

**Reconciliation tools find the mismatch. Vishwas remembers what it turned out to be.**
***Articles***
1)https://masetti07.substack.com/p/when-the-accountant-goes-on-leave?r=96ax15&utm_campaign=post&utm_medium=web&showWelcomeOnShare=true
2)https://medium.com/@2420030536cse/turning-a-vendors-broken-promise-into-hindsight-evidence-6a02723964f7
3)https://dev.to/lavanya_k_763cf9ef4813e9f/hindsight-made-my-gst-agent-remember-vendors-4gj9

Video:
https://www.youtube.com/watch?v=ujmvsht5EuY&feature=youtu.be

Vishwas ("trust") is a GST reconciliation workspace for Indian finance teams. Every month the accountant matches the
purchase register against GSTR-2B and gets a list of mismatches. A rule-based tool gives the same textbook action
for every one of them. Vishwas **remembers** how each vendor behaves, dimension by dimension (filing timing, amount
accuracy, tax heads, invoice details, responsiveness, duplicates), and what each past mismatch actually turned out to
be. So it can tell a timing difference that will fix itself next month from a vendor that is putting input tax credit
(ITC) at real risk.

Memory is the product. With [Hindsight](https://hindsight.vectorize.io/) (agent memory by Vectorize) Vishwas advises
from each vendor's history; without it, it falls back to a rule-based matcher that gives the textbook action for
every mismatch. That contrast is the demo.

> *Informational only, not tax advice.* The company, vendors and GSTINs in the sample data are fictional.

![Money at risk](docs/screenshots/money-at-risk.png)

## What memory changes (real run, August 2026 sample)

| Case | Without memory (Groq, no history) | Vishwas with memory |
|---|---|---|
| Sri Balaji Traders, SBT/2026/0118 missing from GSTR-2B | Recommend: "Add the missing invoice to the books after confirming its validity with the vendor" (wrong: it is our invoice, the vendor files late) | **Recommend.** "Possible timing difference: this vendor's missing invoices appeared in the next GSTR-2B in 4 of 4 past cases." |
| Kaveri Packaging, KPL/0631 missing | Review: "Investigate the discrepancy with the supplier before claiming the Rs 10,800 ITC" | **Review.** "Potential payment risk: this vendor has 3 past unresolved cases (Rs 42,000 exposure) and broke a written promise to file by 20 Jul 2026." Evidence cites the 6 Jul letter. |
| Charminar Fasteners, same invoice booked twice | **Auto-resolve** | **Escalate.** Duplicates are never decided from history. |
| Godavari Steel, "142" vs "INV/26-27/0142" | Auto-resolve | **Auto-resolve** under the approved `FORMAT_ONLY` rule: "4 of 4 past format differences were confirmed typos". Audited and reversible. |
| Sri Balaji **Enterprises** (look-alike, different GSTIN), first missing invoice | Review, same wording as every other missing invoice | **Review.** "This vendor has no past cases on filing timing, so history cannot vouch for this invoice." It never borrows Balaji Traders' history. |

The baseline is a real model call, so its wording varies from run to run; the rows above are from one recorded run.

Then the September GSTR-2B arrives (Step 4). Vishwas judges every August case against it: Balaji Traders' invoice
appears (5 of 5 now), Kaveri's June invoice appears 3 months late while July stays at risk, Metro amends, Nandi's
credit note settles. It records whether its own recommendations were right and updates its beliefs live.

## How Vishwas uses Hindsight

| Capability | What it does for the accountant | Where |
|---|---|---|
| Bank per client, missions, disposition (`PATCH /config`) | Reflect mission: protect ITC, remember each vendor per dimension, never over-trust history. Retain mission: what to extract. Skepticism 5, literalism 5. | `memory/MemoryDesign.java`, `memory/BankSetup.java` |
| Directives (5) | Cite month, amount and outcome; separate history from confidence (<3 cases = thin); never a payment action; broken promises are evidence; weigh Vishwas's own track record. | `memory/MemoryDesign.java` |
| Retain with backdated timestamps, context labels, stable document ids, metadata, tags `vendor:` `dim:` `type:` `period:` | Every event of the history (detections, outcomes, communications, recommendations, decisions) at the real date it happened. | `memory/MemoryWriter.java` |
| Custom `observation_scopes` `[[vendor],[vendor,dim],[dim]]` | Beliefs per vendor, per vendor + dimension, and per dimension across vendors. | `memory/MemoryWriter.java#scopes` |
| Explicit entities with `resolve_entities=false` | Sri Balaji Traders and Sri Balaji Enterprises never merge (tested). | `memory/MemoryWriter.java`, `SeedLoadIntegrationTest` |
| `update_mode: "append"` | One document per vendor communication thread. | `memory/MemoryWriter.java#communication` |
| `POST /files/retain` | Vendor letters (PDF) with the vendor's tags; two in the seed, more via upload. | `memory/hindsight/HindsightClient.java#retainFiles`, `workflow/LetterService.java` |
| Async batch retain + `/operations` polling (webhook when public) | History load, one month per batch, waiting for consolidation so beliefs evolve. | `memory/HistoryMemoryLoader.java`, `api/WebhookController.java` |
| Recall (observations, facts, `query_timestamp`, `temporal_window`) | "What Vishwas learned", the evidence drawer, the assistant's "what happened last month". | `memory/VendorMemory.java`, `assistant/AssistantTools.java` |
| Reflect with `response_schema`, vendor-scoped tags, facts included, in parallel | One reflect per vendor with open cases: category, ranked cause hypotheses with evidence, next step, confidence. | `advisor/MemoryAdvisor.java`, `advisor/AdviceService.java` |
| `GET /memories/{id}/history` | "How Vishwas's view of this vendor changed", month by month. | `memory/VendorMemory.java#beliefHistory` |
| Mental models | "Money at risk briefing" and "Vendor watchlist", refreshed after each processed month. | `memory/MentalModels.java` |
| Knowledge pages | `Vendors/<name>` per vendor, plus the vendor dossier export. | `memory/KnowledgePages.java`, `workflow/DossierService.java` |
| Curation (`PATCH /memories/{id}`, state + reason; `/consolidate`) | "Correct this history": edit or invalidate a fact, audited, beliefs rebuilt. | `memory/CurationService.java` |
| Experience learning loop | Recommendations, decisions (with reasons) and whether each proved right are retained; accuracy is computed in the DB. | `advisor/LearningLoop.java` |

Every endpoint and field was checked against `hindsight-docs/static/openapi.json` (API v0.10.1).

## Screens

| | |
|---|---|
| ![Investigation brief](docs/screenshots/investigation-brief.png) | ![Vendor profile, dark theme](docs/screenshots/vendor-profile-dark.png) |
| ![September verdicts](docs/screenshots/next-month-verdicts.png) | ![Beliefs updating live](docs/screenshots/next-month-beliefs.png) |
| ![Action center](docs/screenshots/action-center.png) | ![Month-end close](docs/screenshots/month-end-close.png) |

## Architecture

```mermaid
flowchart LR
    subgraph Browser["Browser (vanilla JS + marked)"]
        UI["Steps 1-4 · Action center · Assistant · Month-end close · /health"]
    end
    subgraph App["Vishwas (Spring Boot 3.3, Java 21)"]
        API["api (REST, JSON errors)"]
        ING["ingest: CSV + simplified GSTR-2B, normaliser, GSTIN"]
        MAT["matching: deterministic matcher, candidates"]
        OUT["outcomes: outcome detector, promises, money"]
        ADV["advisor: guardrails, confidence, ledger headlines, parallel reflect, baseline"]
        WF["workflow: month processor, action center, learning loop, close"]
        AS["assistant: tool-grounded Q&A"]
        MEM["memory: writer, history loader, beliefs, curation, pages"]
        DB[("H2 / PostgreSQL via Flyway")]
    end
    HS[("Hindsight Cloud: bank per client")]
    GQ[("Groq: openai/gpt-oss-120b")]
    UI --> API --> WF & ADV & AS
    WF --> ING --> DB
    WF --> MAT & OUT
    MAT & OUT --> DB
    ADV --> MEM
    AS --> MEM & DB
    MEM <--> HS
    ADV --> GQ
    AS --> GQ
    WF --> GQ
```

**Design rule: memory decides and explains; the ledger supplies the numbers.** The matcher, outcome detector, money
arithmetic, confidence and accuracy metric are deterministic Java over the database. Hindsight supplies what a
database cannot: judgement over each vendor's history, the words of letters and promises, patterns across months,
and Vishwas's own track record. Every rupee figure and case count a user sees comes from the ledger, so a
recommendation never says "8 of 8" when the ledger says 4 of 4.

## Run it locally

Requirements: Java 21, Maven 3.9. Optional: Docker, Node 18+ (only for the frontend's small test file).

```bash
cp .env.example .env        # then fill in HINDSIGHT_API_KEY, GROQ_API_KEY, HINDSIGHT_BANK_ID yourself
mvn -q package
java -jar target/vishwas.jar  # http://localhost:8080
```

- Without keys Vishwas still runs: memory off means textbook actions, baseline off means textbook in the grey column.
- `HINDSIGHT_BANK_ID=vishwas-dev` for development and `vishwas-demo` for the demo. Each bank gets its own local
  database file (`data/<bank>.mv.db`), so the two never mix.
- Production: `DATABASE_URL=postgres://user:pass@host:5432/db`, `LOG_FORMAT=json`,
  `docker build -t vishwas . && docker run -p 8080:8080 --env-file .env vishwas`.
- Tests: `mvn test` (158 tests, Hindsight and Groq stubbed; they never read `.env`) and
  `node --test src/test/js/api-replay.test.mjs`.

## Demo day

**The evening before** (history loading takes 7 to 18 minutes on Hindsight Cloud):

1. Stop any running Vishwas.
2. Run `powershell -ExecutionPolicy Bypass -File scripts\prepare-demo.ps1 -Url http://localhost:8080`
   (macOS/Linux: `scripts/prepare-demo --url http://localhost:8080`). It starts Vishwas on `vishwas-demo`, resets it,
   loads the history, waits for consolidation and prints `READY` with the memory and observation counts.
3. Rehearse Steps 2 to 4 once with **⋯ → Record this run**, then **Stop and save recording**. That is your offline
   replay if the network fails tomorrow.
4. Run `prepare-demo.ps1` **again**, so the bank is clean (the rehearsal used up August and September). Leave the
   server running, or restart it with `HINDSIGHT_BANK_ID=vishwas-demo`.

**30 minutes before:**

1. Open `http://localhost:8080/health`: Hindsight reachable, bank setup READY, bank `vishwas-demo`, around 210
   memories, Groq reachable.
2. Open `http://localhost:8080`: Step 1 shows done with the summary line, Step 2 is ready. Do not click Reconcile yet.
3. Browser at 100% zoom, 1366×768 or larger, light theme, other tabs closed. Keep the laptop on power, with sleep off.
4. If `/health` shows Hindsight unreachable: use **⋯ → Play offline replay**. The yellow banner says so on every screen.

### The 2-minute script

| Time | Click | Say |
|---|---|---|
| 0:00 | (Step 1 already done) | "Reconciliation tools find the mismatch. Vishwas remembers what it turned out to be. It has watched this company's vendors for four months." |
| 0:10 | **Reconcile August 2026** | "Fifteen mismatches. A normal tool gives each the same textbook action. Vishwas asks each vendor's memory, in parallel." |
| 0:25 | Point at the three numbers | "What needs review, what is likely just timing, and Rs 8,640 actually lost to date. Open exposure is never called a loss." |
| 0:35 | Point at Balaji Traders, then the grey column | "Four of four times this vendor's missing invoices showed up next month: check the next refresh before chasing. Without memory: send a reminder." |
| 0:45 | Point at Kaveri | "Same kind of mismatch, opposite advice: three unresolved invoices, Rs 42,000, and a broken written promise. Review." |
| 0:55 | Point at Charminar | "The same invoice booked twice. Vishwas never decides duplicates from history: escalate. The grey column shows how a model without memory treats it: like any other mismatch." |
| 1:05 | Open Kaveri's case, scroll | "Every claim has evidence: the past cases from the ledger, the letter, and a separate confidence. Past reliability never proves today's invoice." |
| 1:20 | **Vendor profile** on Balaji Enterprises | "A look-alike name, a different GSTIN. It never inherits Balaji Traders' history." |
| 1:30 | Step 4: **Load September 2026 GSTR-2B** | "Next month arrives. The ledger judges August instantly: Balaji Traders' invoice appeared, five of five now. Metro amended. Kaveri's July invoice is still at risk." |
| 1:45 | Point at the highlighted cards and the accuracy row | "Vishwas checks its own recommendations against what happened. Memory is consolidating the new beliefs live." |
| 1:55 | | "It gets smarter every month, and it shows its evidence every time." |

The belief swap takes about 2 minutes after the click (measured 1:56). If it hasn't landed by the end, open Balaji
Traders' profile: "How Vishwas's view changed" already shows April to July.

## Sample data

Deccan Home Appliances Pvt Ltd, Hyderabad (GSTIN 36AAGCD4821M1ZG); accountant Lakshmi Prasad, CFO Srinivas Reddy;
15 fictional vendors with valid-format GSTINs. History runs April to July 2026, the live month is August 2026 (its
GSTR-2B is generated 14 Sep), and a prepared September 2026 GSTR-2B drives Step 4. The files are generated
deterministically by `src/test/java/com/vishwas/tools/SeedGenerator.java`; `SeedScenarioTest` replays them and checks
every vendor's story. The GSTR-2B files use a **simplified JSON format** (`docs/gstr2b-simplified.md`), not the portal
schema. GST rates are 5% and 18%, since the 12% slab was merged in the September 2025 rate rationalisation.

## Module map

`ingest` → `matching` → `outcomes` → `memory` → `advisor` → `workflow` → `assistant` → `api` → `web`, plus `config`,
`llm` and `demo`. Ownership, dependency rules and how to add things are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Known limitations

- **Docker image build not verified.** The multi-stage `Dockerfile` has not been built on the development machine
  yet (Docker was not running); `mvn package` and `java -jar` are what has been tested.
- **Thin-history categories can vary between runs.** For vendors with fewer than 3 past cases on a dimension
  (e.g. Metro Logistics and Nandi Electricals, 2 cases each), memory's category can come back as Recommend in one
  run and Review in another. Guardrails still apply (duplicates always escalate, auto-resolve only via the approved
  rule, material exposure with thin history is lifted to Review), and confidence is shown as thin.
- **Counts and amounts come from the ledger, not from memory's wording.** Hindsight's consolidated observations
  sometimes over-count (one invoice's several facts read as several cases). Every number on cards, headlines,
  money totals and the accuracy metric is computed from the database; memory's own text is labelled as such.
- **Timings depend on Hindsight Cloud:** history load took 7 to 18 minutes, reconcile + advice 13 to 18 s, and the
  post-September belief update about 2 minutes in our runs. Hence the night-before `prepare-demo` script and the
  offline replay.
- Simplified GSTR-2B format (not the portal schema) and fictional sample data only.

## Roadmap

- Adapter for the real GST portal GSTR-2B JSON, and purchase-register import from Tally and Zoho exports.
- Multi-company workspaces (one bank per client company is already the design) and user accounts with roles.
- Scheduled reconciliation on the 14th, and e-mail sending with vendor replies parsed into the thread.
- Section 16(4) time-limit tracking, so an unresolved case becomes a confirmed loss only when the ITC window closes.
- Tune Hindsight's observation wording so its own counts match the ledger (the UI already shows ledger counts).
