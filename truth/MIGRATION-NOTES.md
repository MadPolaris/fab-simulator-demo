# MIGRATION-NOTES — pilot dialect → truthsrc v0.4 (2026-10-18)

Pure format migration of `truth/` (fab-simulator-demo). Content did not change:
every pilot string (1024 checked, whitespace-normalized) survives somewhere in
the new tree, except the intentionally retired files below. No Scala/code files
touched.

## Gates (both green)

```bash
node bin/truthsrc.js validate --tree <truthDir>    # ✅ 0 hard · 26 notice
node bin/truthsrc.js roundtrip --tree <truthDir>   # ✅ roundtrip identity — 122 atoms, canonical byte-stable
```

Run from a checkout of `zhongdj/metarch-truthsrc` (`/tmp/truthsrc-work/` on the
migration machine). Notices: 25× AG1/AG2 (inbox sticky names are PascalCase,
slice commands kebab-case — the pilot's own naming; renaming would change
content) + 1× ES4 (`WorkOrderCreated` contains "create" — it is a business
fact: a work order being created).

## What changed per kind

- **Root**: `tree.yaml` → `workshop.yaml`. NOTE: it carries
  `schemaVersion: truthsrc/v1`, not `v0.4` — `compile` hardcodes the canonical
  `schemaVersion` to `truthsrc/v1` and `decompose` re-emits it, so the roundtrip
  gate requires `v1` here byte-for-byte.
- **Aggregates** (`aggregates/<id>/aggregate.yaml`): pilot `identity` →
  `payload.identifier {name, dataType, origin, origin_zh?}` (RF2);
  `state_slots[].type` → `stateFields[].dataType` (RF3); `lifecycle` →
  `statusEnum` + `statusTransitions {event: status}` + `stateGates
  {command: {fromStates, toState}}`; `invariants`/`progress_recording`/
  `change_recording` → `payload.aggregateRuleRefs [{ruleId, desc, desc_zh?}]`;
  new `order:` block with inline JSON `commands`/`events` (V5/V6). `entity`:
  FabPipelineRun / WaferLot / WorkOrder.
- **Slices** (`aggregates/<id>/slices/<cmd>.slice.yaml`): header is now
  `kind/slice`, `aggregate` (= aggregate NAME, V4), `command` (kebab id);
  command body moved into `payload` JSON (`name` ≡ command, V4;
  `parameters[].dataType`, RF3); events into the `events:` JSON block with
  `bindings: []` / `foldAssignments: []`. Pilot `title` (human name, e.g.
  "Start Pipeline Run") kept as `payload.title` since `payload.name` must be
  the kebab id.
- **Policies** (`policies/*.policy.yaml` → `policies/policy-rf-<slug>-<seq>.policy.yaml`):
  pilot `invokes: - slice: X` → `- command: X`; trigger/invoke rows are exactly
  `{event, aggregate}` / `{command, aggregate}` (V8 deep-equal); `payload.links`
  ≡ the projection (V8); rules kept verbatim. `origin.seq` continues the
  `(refining, reconstructed)` counter after the inbox refining notes (45–49).
- **Value objects**: `fields[].type` → `dataType` (V10); `variants` (equipment-area,
  equipment-status, ocap-action-plan, wafer-classification) kept verbatim in
  payload. Two files renamed to decompose's `<slug(name)>` rule:
  `fab-run-state.vo.yaml` → `run-state-record.vo.yaml`,
  `transaction-number.vo.yaml` → `cross-lot-transaction-number.vo.yaml`.
- **Read models**: `fields[]` gained `source` per field (V11) + `sourceAggregate`
  (single, resolving to an aggregate NAME); full pilot `sources` list kept as
  `payload.sources` (names); `visible_to` kept (role-permission matrix).
- **Pipeline**: headers `kind/id/name/name_zh/description/description_zh` +
  `failure_semantics(_zh)` (single-line), then the `steps:` JSON array (25 steps,
  field name `steps`, PL1/PL2).
- **Scenarios**: headers + `script:` JSON `{scale, chains, outcome_rules, notes}`.
  The trailing `# 2026-10-18 ARBITRATED (true chaos)` comments moved into
  `script.notes` (comments can't roundtrip).
- **Inbox notes** (84 notes, 11 product dirs): headers reduced to
  `kind/type/name` (+ `origin: {stage, board: reconstructed, seq}`), everything
  else into the verbatim `payload` JSON (`type`/`name` ≡ headers, V3).
  Filenames follow decompose: `<type>-<stagecode>-<slug(name)>-<seq>.yaml`.
  `origin.seq` is 0..n-1 per stage across its product dirs (dir order).
  Event notes: `payload.info = {timeline: "fab-pipeline", sequence: <pilot
  sequence>}` (ES1/ES2). Role notes keep `can_issue`/`can_view` (kebab role ids);
  readModel notes keep `visible_to`. Added `payload.links` with resolvable
  references (command→event, role→command, externalSystem→event) to satisfy
  CS1/CS3/CS4/CS6 as notices are cheap but silence is cheaper; pilot trigger
  text (incl. Chinese role names) kept verbatim in `payload.info`.

## What was dropped and why

- **`truth/types.yaml`** — the kind vocabulary now lives in the toolchain's
  `SCHEMA.md`; a per-tree copy would drift.
- **`truth/tree.yaml`** — replaced by `workshop.yaml` (schemaVersion + sessionOrder).
  Provenance text ("scenario A — read-only reverse engineering…") is not
  representable in the v0.4 root shape; it remains in git history and
  `EXTRACTION-NOTES.md`.
- **`truth/tools/`** (the forked `validate-tree.py`) — the machine gate is the
  shared truthsrc toolchain now; a forked validator would drift from it.
- **YAML folded scalars (`description: >`)** — the v0.4 parser (`parseDoc`)
  throws "malformed line" on them. Every multi-line text was joined into a
  single-line scalar (spaces); nothing was cut.
- **10-policies/ inbox cards (5 notes)** — each card is a condensed duplicate of
  its `policies/*.policy.yaml` atom (verified: no unique facts), and the
  toolchain cannot roundtrip `kind: note` + `type: policy` (decompose coerces
  every `type: policy` payload into a policy atom, breaking tree byte-identity;
  the reference example tree carries policies only as atoms). The cards'
  full text is preserved verbatim in each atom's `payload.workshopCard`.
  The now-empty `10-policies/` dir was removed per "an empty product is worse
  than an absent one" (reason recorded in `inbox/README.md`).

## Judgment calls

- **Identifiers (RF2)**: fab-pipeline-run → `workOrderId` ("One work order, one
  run"); lot → `lotId` ("Entity id == lotId"); work-order → `workOrderId`.
  Full grain notes kept in `identifier.origin(_zh)`.
- **lifecycle → stateGates/statusTransitions**: only slice commands appear in
  `stateGates`; transitions via non-slice commands are recorded as
  `aggregateRuleRefs` (`lifecycle-unmodeled-commands`): lot's
  create-lot/seal-lot/release-wafers, work-order's
  record-lot-completed/complete-work-order/record-lot-failed.
- **Note type remapping** (pilot → NOTE_TYPES, original in `payload.info.pilotType`):
  `valueObject`/`aggregateState` → `entity`; `aggregateRelation`/`lineage` →
  `relation`; `messageFields` → `field`; `useCase` → `story`.
- **Read-model `source` derivations (V11)** — honest best-effort per field:
  timeline/outcomes/currentStep ← `eventField:step-completed.*`;
  passCount/scrapCount ← `eventField:run-completed.*`;
  recovery breakpoint/recovered/faultHistory ←
  `eventField:step-completed.completedStepCount` / `eventField:run-recovered.workOrderId` /
  `eventField:step-failed.error`; equipment-board areas/machines ←
  `eventField:step-failed.error` (error counts feed OCAP equipment conditions);
  event-feed frames ← `eventField:run-started.workOrderId` (stream seed);
  scenario-ledger entries/totals ← `eventField:run-completed.*`.
  `sourceAggregate` is singular per V11: "Fab Pipeline Run" everywhere
  (event-owning aggregate); the full pilot `sources` list is kept in
  `payload.sources`.
- **V9 renames**: none needed — policy triggers/invokes already used kebab
  pool/slice ids. One reconciliation: the pilot's `LotNotificationGuarantee`
  *card* recorded `invokes: []`, but its policy atom invokes `start-pipeline-run`;
  decompose requires non-empty `info.invokes` for policy-typed payloads, and the
  card is retired anyway — the atom is authoritative (noted in the atom's
  history via `workshopCard`).
- **Added links (judgment)**: `ProcessEquipment` → `event:StepFailed`
  (equipment faults surface as step failures); event-note → producing-command
  links use kebab slice ids; `run-failed` has a slice definition but no 01
  sticky note, so the command note links `event:run-failed` (kebab, resolves
  via the pool).
- **Deviations from the "target shapes" sketch forced by the roundtrip gate**
  (decompose's byte-exact emission is the real spec): atoms carry only the
  headers decompose emits (`note`: kind/type/name; `policy`: kind/type/name;
  `aggregate`: kind/name; `slice`: kind/aggregate/command; catalogs: kind/name) —
  `id`/`name_zh`/`description` live in `payload`; note/policy filenames and
  `origin` key order/shape match decompose exactly; `workshop.yaml` uses
  `schemaVersion: truthsrc/v1`.

## Validation commands (exact)

```bash
node bin/truthsrc.js validate --tree <truthDir>
# ✅ 0 hard · 26 notice
node bin/truthsrc.js roundtrip --tree <truthDir>
# ✅ roundtrip identity — 122 atoms, canonical byte-stable
```

Migration script (one-shot, not committed): `/tmp/migrate_fab.py` — reads the
pilot tree via PyYAML, emits v0.4 atoms with a byte-exact replica of the
toolchain's `emitDoc`.
