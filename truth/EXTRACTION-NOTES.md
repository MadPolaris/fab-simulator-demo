# Extraction Notes — fab-simulator-demo truth tree

Method: scenario A — read-only reverse engineering from source code
(`~/workspace/split/fab-simulator-demo/`, 2026-10-18). No code was generated
from this tree; the tree records code truth, it does not arbitrate it.

Language rule (user-mandated 2026-10-18): the tree is written in **problem-space
vocabulary** (DDD ubiquitous language) — fab terms a business person reads:
工单、Lot、晶圆、设备区、量测、OCAP、返工、报废、断点续跑. Solution-space
vocabulary (engines, journals, snapshots, cursors, sharding, serialization
formats) does not appear in the domain content. Where the code's mechanism
matters, the tree states the *business semantic* ("interrupted runs resume
from the breakpoint; finished steps are not repeated") and leaves the
mechanism out.

## What was extracted

- 3 aggregates: fab-pipeline-run (the resumable run), lot (wafer lots +
  sub-lot split/merge/scrap), work-order (order accounting)
- 1 pipeline: fab-pipeline — the full step vocabulary in canonical flow order
- 11 value objects: equipment-area (10 areas), equipment-status, foup,
  wafer-classification, ocap-action-plan (6 variants), ocap-rule,
  equipment-config, decision-config, step-error, transaction-number, fab-run-state
- 4 policies: ocap-evaluation, measurement-classification,
  transaction-number-reuse, wafer-disposition
- 5 read models: pipeline-timeline, equipment-board, event-feed,
  scenario-ledger, recovery-status
- 2 scenarios: m35-demo (crash/OCAP showpiece), fab-demo-tour (happy path)

## Deliberately omitted (with reasons)

- **Akka/Play wiring mechanics** — actor spawning, sharding setup, route
  tables, websocket plumbing. Solution space; the tree records what the
  business does, not how the runtime hosts it.
- **View template details** — the 6 Twirl pages' markup/CSS/JS. The tree
  records what each page *shows* (read models), not how it renders.
- **Test specs** — they verify the code; the tree is the domain truth the
  code is verified against. Different job.
- **Unwired legacy traits** — `SubBatchProcessor`/`AreaScheduler` (if still
  present and uncalled): noted but not extracted; nothing in the fab flow
  calls them.
- **Simulation internals** — RNG draws, timer scaling arithmetic, sleep
  durations. The tree records the *knobs* (faultProbability,
  speedMultiplier) and their business meaning, not the math.
- **CreditBalance / MonthlyIncome aggregates** — see NEEDS CALL #2.

## ARBITRATED 2026-10-18 — user decisions on the 7 ambiguous spots

### 1. 自发故障真随机 → 真混沌 ✅
**Decision:** 真混沌 (true chaos). Spontaneous equipment faults use unseeded
randomness — two runs of the same scenario may fail different wafers, "结果不保证重复".
Only the scripted fault injection in the demo scenarios is deterministic.
**Tree:** recorded in `m35-demo` and `fab-demo-tour` scenario atoms.
**Code:** no change.

### 2. 银行余额那套聚合 → 已移除 ✅
**Decision:** remove. `CreditBalanceAggregate` / `MonthlyIncomeAndExpenseSummaryProjection`
and their 35 companion files were bank-domain leftovers from the playAkkaCQRS move,
unreferenced by any fab flow.
**Code:** 37 files deleted (36 Scala + `credits.proto`); `ApplicationBootstrap`
unmixed from `CreditBalanceBootstrap` / `MonthlyIncomeAndExpenseBootstrap`;
`AppSagaContext` dropped the `creditBalances` field (fab saga only reads `lots`).
Verified zero references outside the removed cluster before deletion.
**Tree:** already excluded; this note records the removal.

### 3. 动态 POR 为正统 ✅
**Decision:** dynamic POR is canonical for NEW scenarios. The static
`FabScenarioPipeline` step list is frozen legacy — existing demos keep running on it,
but no new scenario should be built on it.
**Tree:** recorded in the `dynamic-por-execution` step of `fab-pipeline.pipeline.yaml`.
**Code:** no change.

### 4. 两本账是 CQRS 读写分工 ✅
**Decision:** it IS CQRS — authority follows usage.
- Write-side truth: the journals. Run journal = per-step progress records
  (what recovery replays); lot journal = lot facts.
- Read-side projections: the event feed and the pipeline timeline — UI only,
  may lag or be rebuilt, never drive recovery.
- If the feed says a step completed but the run journal disagrees, the journal wins.
**Tree:** recorded in `event-feed` and `pipeline-timeline` read-model atoms.
**Code:** no change (no conflation found — recovery already replays only the journal).

### 5. 双重"旧运行作废"锁 → 两道都保留 ✅
**Investigation (2026-10-18):** the two guards protect different concurrency
primitives, not the same race:
- **Child `RunRegistry`** (monarch-persistence, per child incarnation): guards
  *actor messages* — a superseded child incarnation's observer callbacks
  (`onStageComplete` etc.) are dropped instead of journaling into the new run.
- **Parent `PipelineRunRegistry`** (per parent incarnation): guards *dispatcher
  futures* — the interpreter's in-flight Future chain lives on the
  ExecutionContext, not in any actor; no message guard can stop it. The
  per-step-entry `runToken()` check fails it with `StaleRun` at the next step
  boundary so a superseded run stops issuing real side effects (lot tells,
  UI publishes). It also guards `publish`/`stageProgressFn`.
- Neither subsumes the other: the child guard cannot cancel a running Future;
  the parent guard cannot intercept another actor's messages. Belt-and-braces
  it is not — it is layered defense, and the code comment already says so.
**Decision:** keep both; the layering is now documented here.
**Tree:** this note is the record (business rule: single-effective-run).
**Code:** no change.

### 6. Lot 通知改成等确认 ✅
**Decision:** acknowledged delivery. The pipeline's lot notifications
(measure / classify / split / merge / scrap / seal) are now tracked per step:
the step only counts as done once every notification is confirmed. An
unconfirmed notification fails the step with `LOT_ACK_TIMEOUT` (5s), flowing
through the normal step-failure path (OCAP evaluation).
**Code:**
- New `LotAckGate` (per-step tracking gate; `fireAndForget` default preserved
  for the direct-invocation path).
- 54 call sites: `ctx.lotRef ! Cmd(..., ctx.ignoreLotReply)` →
  `ctx.notifyLot(Cmd(..., _))`; the interpreter drains the gate before a step
  counts as complete.
- New `LotAckGateSpec`: 4 tests green — seal-on-confirm, seal-fails-on-timeout,
  timeout surfaces as `LOT_ACK_TIMEOUT` step failure, fire-and-forget preserved.
**Tree:** new `lot-notification-guarantee` policy atom; NEEDS CALL #6 retired.

### 7. Lot 是恢复的权威 → 原则已定，实现有缺口 ⚠️
**Decision (principle):** lots are the system of record. On pipeline recovery,
the run's position should reconcile FROM the lot actors' reported states —
a lot's position in its route IS the state (this is how real fabs work: the MES
is authoritative, the line reconciles from equipment/MES-reported positions).
**Gap (honest):** not fully implemented. Two blockers:
- The lot protocol's `GetLotState` returns only a `LotConfirmation`, not the
  wafer-level state needed for reconciliation — a real implementation needs a
  new query (e.g. `GetLotWafers`) on the lot protocol.
- The child's `PersistentMonarch` recovery is journal-driven; there is no
  injection point for externally reconciled state — adopting lot state would
  require recovery-flow surgery.
**What already converges (no silent divergence):** with #6's ack gate, the run
can never get *ahead* of the lots (a step completes only after lot confirm);
lot commands are idempotent (`RecordWaferMeasured` etc. reply without
re-persisting when already recorded), so re-execution after recovery converges
instead of duplicating. The remaining gap is *explicit* reconciliation on
recovery — recorded here as future work, not pretended done.
**Tree:** this note + the principle recorded in the `lot` aggregate.
**Code:** no change (deliberate — see gap above).
