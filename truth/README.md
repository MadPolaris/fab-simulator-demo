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
| `tree.yaml` | Root manifest: name, version, provenance, full index |
| `types.yaml` | The **type vocabulary** — the 7 record kinds every file uses |
| `aggregates/` | The three consistency boundaries: `fab-pipeline-run`, `lot`, `work-order` |
| `pipelines/` | The fab step vocabulary (step order, slots, failure codes) |
| `value-objects/` | Shared immutable domain types (areas, OCAP plans, configs, …) |
| `policies/` | Reactive domain rules (OCAP, classification, transaction-number reuse, disposition) |
| `read-models/` | What the web UI serves (timeline, equipment board, event feed, …) |
| `scenarios/` | The demo scripts (M3.5 crash/OCAP demo, standard tour) |
| `tools/` | `validate-tree.py` — the machine gate (see below) |

Each directory has its own `README.md`. Every atom file starts with
`kind` / `id` / `name` / `description` — read those four lines and you know what it is.

## Conventions

- **Stable IDs, never copied text.** Cross-references use `id` (e.g. `aggregate: lot`,
  `event: step-completed`). If a name changes, the id stays.
- **One file = one typed record.** The `kind` comes from `types.yaml`; the validator
  enforces required fields per kind.
- **Chinese alongside English** where the concept is domain-heavy (fab / OCAP vocabulary),
  following the repo's `README-zh.md` precedent.
- **No machine smell.** Fields that exist only for byte-identity or compiler bookkeeping
  (ordering keys, sequence numbers, codegen hints) do not belong here — the compiler owns those.

## Machine gate

```bash
python3 truth/tools/validate-tree.py truth/
```

Checks: every file parses, kinds/ids valid, ids unique, all cross-references resolve,
every aggregate has ≥1 slice, every policy trigger resolves to a produced event.
Humans decide domain truth; the tool only reports structural breakage.

## What this tree is NOT

- Not a second copy of the code — it captures *domain facts* (step vocabulary,
  OCAP plans, classification thresholds, terminal states), not implementation.
- Not generated from a workshop — it was extracted from working code, so the vocabulary
  is pipeline/slice/policy rather than event-storming sticky notes.
- Ambiguities the code didn't settle are marked `[NEEDS CALL]` in `EXTRACTION-NOTES.md`
  — the tree records code truth, it does not arbitrate it.
