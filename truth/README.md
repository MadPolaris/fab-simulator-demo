# Truth Tree — fab-simulator-demo

This directory is the **domain truth** of the fab simulator demo, reverse-engineered
from the source code (scenario A: read-only extraction, no code was generated from it).

## What this demo is

Wafer **lots** (batches of wafers in FOUPs) are driven through **equipment areas**
(lithography, etch, metrology, … — 10 areas) by a **resumable step queue**:
every finished step records its progress, so an interruption resumes from
the breakpoint instead of repeating finished steps. Wafers are measured
(CD-SEM), classified (PASS / BORDERLINE / FAIL / SCRAP), and routed — rework,
scrap, hold, or release. When a step fails, **OCAP** (out-of-control action
plan) rules evaluate and save the step inline so the queue still advances.
Cross-lot transactions reuse their original numbers after recovery instead
of opening duplicates.

Two layers: `core/` holds the equipment simulators (idle → busy → idle actors with
fault injection); `web/` holds the Play UI plus the full CQRS stack
(domain / application / infrastructure).

## How to navigate (top-down, no tooling needed)

| Directory | What's in it |
|---|---|
| `workshop.yaml` | Tree root: schema version + workshop session order |
| `inbox/` | The 13 workshop products, one directory per product (event flow, commands, aggregates, …) |
| `aggregates/` | The three consistency boundaries: `fab-pipeline-run`, `lot`, `work-order` (+ one slice per command) |
| `pipelines/` | The fab step vocabulary (step order, slots, failure codes) |
| `value-objects/` | Shared immutable domain types (areas, OCAP plans, configs, …) |
| `policies/` | Reactive domain rules (OCAP, classification, transaction-number reuse, disposition) |
| `read-models/` | What the web UI serves (timeline, equipment board, event feed, …) |
| `scenarios/` | The demo scripts (M3.5 crash/OCAP demo, standard tour) |

Format: **truthsrc v0.4** (see `MIGRATION-NOTES.md` for the migration from the
pilot dialect). The atom vocabulary (`kind`) and the machine gate live in the
truthsrc toolchain (`zhongdj/metarch-truthsrc`), not in this tree.

Each directory has its own `README.md`. Every atom file starts with the
`# ═══ truthsrc atom ═══` banner, then scalar header lines (`kind`, `name`, …),
then verbatim JSON blocks (`payload`, `events`, `steps`, `script`) — read the
headers and you know what it is; the JSON is the workshop's own content,
byte-preserved.

## Conventions

- **Stable IDs, never copied text.** Cross-references use `id` (e.g. `aggregate: lot`,
  `event: step-completed`). If a name changes, the id stays.
- **One file = one typed record.** The `kind` comes from the truthsrc v0.4
  vocabulary (see the toolchain's `SCHEMA.md`); the validator enforces required
  fields per kind.
- **Chinese alongside English** where the concept is domain-heavy (fab / OCAP vocabulary),
  following the repo's `README-zh.md` precedent.
- **No machine smell.** Fields that exist only for byte-identity or compiler bookkeeping
  (ordering keys, sequence numbers, codegen hints) do not belong here — the compiler owns those.

## Machine gate

From a checkout of `zhongdj/metarch-truthsrc` (the toolchain is no longer
vendored under `truth/tools/`):

```bash
node bin/truthsrc.js validate --tree <truthDir>    # V*/ES*/CS*/AG* structural + stage rules (0 hard required)
node bin/truthsrc.js roundtrip --tree <truthDir>   # compile∘decompose∘compile byte-identity
```

Checks: every file parses, kinds valid, `order.commands`/`order.events` close
exactly over the slice/event pool, every policy trigger/invokes resolves,
read-model fields carry a `source` derivation. Humans decide domain truth;
the tool only reports structural breakage.

## What this tree is NOT

- Not a second copy of the code — it captures *domain facts* (step vocabulary,
  OCAP plans, classification thresholds, terminal states), not implementation.
- Not generated from a workshop — it was extracted from working code, so the vocabulary
  is pipeline/slice/policy rather than event-storming sticky notes.
- Ambiguities the code didn't settle are marked `[NEEDS CALL]` in `EXTRACTION-NOTES.md`
  — the tree records code truth, it does not arbitrate it.
