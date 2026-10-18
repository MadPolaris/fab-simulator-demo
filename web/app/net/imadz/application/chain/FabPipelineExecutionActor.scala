package net.imadz.application.chain

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, Recovery}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted => AkkaRecoveryCompleted}
import net.imadz.application.chain.FabExecutionModel.{FabDemoContext, FabDemoState}
import net.imadz.application.chain.FabExecutionModel.{StageError => FabStageError, StageFailedException => FabStageFailedException}
import net.imadz.application.chain.FabScenarioPipeline.PipelineStage
import net.imadz.domain.events.{FabSimulationEvent, GlobalStatusChanged, RecoveryEvent}
import net.imadz.monarch.{StaleRun, StageError => MonarchStageError, StageFailedException => MonarchStageFailedException}
import net.imadz.monarch.persistence.{PersistentMonarch, RunObserver}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * ===== FabPipelineExecutionActor =====
 *
 * Sharded facade over `net.imadz.monarch.persistence.PersistentMonarch`.
 *
 * The hand-rolled `EventSourcedBehavior` that used to drive `FabPipelineProcessor`
 * directly (~500 lines: manual cursor journaling, `RecoveryCompleted` resume,
 * `PipelineRunRegistry` generations) is now split in two:
 *
 *   - **This actor (parent)** keeps the *domain* journal: `Started`,
 *     `StageStarted` / `StageCompleted` / `StageFailed` / `OcapHandled` /
 *     `StageProgress`, `AllCompleted` / `ExecutionFailed`, tagged `"fab-pipeline"`.
 *     The tag, the event schema, and the command protocol
 *     (`StartExecution` / `StopPipeline` / `ExecutionReply`) are unchanged, so
 *     `FabPipelineProjection`, `FabDemoController`'s timeline, and all
 *     `FabDemoService` call sites are untouched.
 *   - **A `PersistentMonarch` child** (`"run"`, journal
 *     `FabPipelineExecutionRun|<workOrderId>`) owns the *mechanics*: per-stage
 *     `StageDone` journaling with protobuf state snapshots, cursor-based resume
 *     on `RecoveryCompleted`, and `RunRegistry` generation guards. The parent
 *     translates child lifecycle callbacks into domain events.
 *
 * == OCAP placement ==
 *
 * OCAP interception lives *inside the stage interpreter*, not in
 * `PersistentMonarch`'s `failurePolicy`: `Monarch` never calls `onStageComplete`
 * for a failure recovered via the failure interceptor (and `PersistentMonarch`
 * does not wire `onStageResolved`), so a `failurePolicy` wiring would leave the
 * cursor behind on every OCAP-resolved stage. Intercepting inline makes an
 * OCAP-resolved stage journal `StageDone` like a clean completion — the cursor
 * advances — while the parent still journals the distinct `OcapHandled` domain
 * event (P2: the journal never records a fake *success* for a failed stage).
 *
 * == Crash recovery ==
 *
 * Parent and child restart together (the child is the parent's child actor):
 *   1. the parent replays its domain journal → `Executing`;
 *   2. `receiveSignal(RecoveryCompleted)` respawns the child, which replays its
 *      own journal and resumes the Monarch queue past the journaled cursors;
 *   3. as a belt-and-braces for the crash window between the parent's `Started`
 *      and the child's `RunStarted`, the parent also re-sends `StartRun` — the
 *      child `Reject`s it when it already has a run, so a stalled run is
 *      impossible.
 * Staleness is guarded twice: the child's engine carries `PersistentMonarch`'s
 * own generation token, and the interpreter-built `FabDemoContext` carries the
 * `PipelineRunRegistry` token (bumped by `StopPipeline`), so a superseded run's
 * callbacks and side effects stay silent.
 *
 * == Deterministic Saga Re-attach ==
 *
 * Saga transactions use [[net.imadz.application.services.SagaIdGenerator.generate]]
 * to produce deterministic UUID v3 transaction IDs, enabling sagas to be idempotently
 * re-attached after recovery. This lives inside the stage bodies
 * (`FabScenarioPipeline.runStage`), which the interpreter calls unchanged.
 */
object FabPipelineExecutionActor {

  val EntityKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("FabPipelineExecution")

  /** Journal tag for [[eventsByTag]] Projection subscriptions. */
  val Tag = "fab-pipeline"

  /**
   * How long the step interpreter waits for lot notifications to be confirmed
   * before failing the step (2026-10-18 arbitration: lot notifications are
   * acknowledged). Local lot actors confirm in milliseconds; 5s is generous.
   */
  val LotAckTimeout: akka.util.Timeout =
    akka.util.Timeout(5, java.util.concurrent.TimeUnit.SECONDS)

  /** PersistenceId type for the PersistentMonarch child (one per parent entity). */
  private val ChildPersistenceId = "FabPipelineExecutionRun"

  /** Child actor name under the sharded parent. */
  private val ChildActorName = "run"

  // ============================================================
  // Protocol (external — unchanged)
  // ============================================================

  sealed trait Command
  sealed trait Event
  sealed trait State
  sealed trait ExecutionReply

  /** Start pipeline execution with the given stages and context. */
  final case class StartExecution(
      scenarioId: String,
      workOrderId: String,
      initialState: FabDemoState,
      stages: Seq[PipelineStage],
      ctx: FabDemoContext,
      replyTo: ActorRef[ExecutionReply]
  ) extends Command

  /** M3.5: Stop/crash the pipeline actor. Causes Behavior.stopped → sharding restarts → RecoveryCompleted. */
  final case class StopPipeline(workOrderId: String) extends Command

  /** Internal: intra-stage progress notification (replaces ctx.publish(GlobalStatusChanged)). */
  final case class StageProgressEvent(status: String, detail: String, phase: String) extends Command

  // ---- Child engine → parent: internal lifecycle callbacks ----

  /** Internal: the child engine started a stage. */
  private final case class EngineStageStarted(cursor: String) extends Command

  /** Internal: a stage failed (structured); journaled BEFORE OCAP runs,
    * mirroring the old `onStageFailed` hook ordering. Sent by the interpreter
    * side-channel because `RunObserver.onStageFailed` only carries a detail string. */
  private final case class EngineStageFailed(cursor: String, error: FabStageError) extends Command

  /** Internal: the child engine completed a stage (cleanly, or OCAP-resolved —
    * see `runState.lastOutcome`). */
  private final case class EngineStageCompleted(cursor: String, runState: FabRunState) extends Command

  /** Internal: the child is recovering; `completedCount` stages already done. */
  private final case class EngineRecovering(completedCount: Int) extends Command

  /** Internal: the child run drained the whole queue. */
  private final case class EngineRunSucceeded() extends Command

  /** Internal: the child run failed (OCAP itself threw, or an unexpected error). */
  private final case class EngineRunFailed(cursor: String, reason: String) extends Command

  /** Internal: the child's `StartRun` reply, forwarded to the original `replyTo`. */
  private final case class EngineStartReplied(replyTo: ActorRef[ExecutionReply], reply: ExecutionReply) extends Command

  // ---- Events (unchanged schema — projection & timeline read these) ----

  final case class StageProgress(
      status: String,
      detail: String,
      phase: String,
      timestamp: Long
  ) extends Event

  final case class Started(
      scenarioId: String,
      workOrderId: String,
      stageCount: Int
  ) extends Event

  final case class StageStarted(
      phase: String,
      timestamp: Long
  ) extends Event

  final case class StageCompleted(
      phase: String,
      timestamp: Long,
      metadata: Map[String, String],
      fabState: Option[FabDemoState] = None
  ) extends Event

  final case class StageFailed(
      phase: String,
      error: FabExecutionModel.StageError,
      timestamp: Long
  ) extends Event

  /** P2: OCAP handling of a failed stage — advances the cursor without claiming success. */
  final case class OcapHandled(
      phase: String,
      error: FabExecutionModel.StageError,
      timestamp: Long,
      fabState: FabDemoState
  ) extends Event

  final case class AllCompleted(
      scenarioId: String,
      workOrderId: String,
      totalWafers: Int = 0,
      passedWafers: Int = 0,
      reworkedWafers: Int = 0,
      scrappedWafers: Int = 0
  ) extends Event

  final case class ExecutionFailed(
      phase: String,
      reason: String
  ) extends Event

  // ---- States (unchanged) ----

  case object Idle extends State

  final case class Executing(
      scenarioId: String,
      workOrderId: String,
      completedPhases: List[String],
      stageCount: Int = 0,
      fabDemoState: Option[FabDemoState] = None
  ) extends State {
    def lastCompletedPhase: Option[String] = completedPhases.lastOption
    def completedCount: Int = completedPhases.size
  }

  final case class Completed(
      scenarioId: String,
      workOrderId: String
  ) extends State

  final case class Failed(
      phase: String,
      reason: String
  ) extends State

  // ---- Replies (unchanged) ----

  case object Accepted extends ExecutionReply
  final case class Rejected(reason: String) extends ExecutionReply

  // ============================================================
  // Child run host: interpreter + observer for one PersistentMonarch child
  // ============================================================

  /**
   * Builds the stage interpreter and run observer for a `PersistentMonarch`
   * child. One host per child incarnation.
   *
   * @param workOrderId    entity/run id
   * @param freshCtx       the `FabDemoContext` from `StartExecution` (fresh start),
   *                       or `None` on recovery (rebuilt via `contextFactory`)
   * @param contextFactory rebuilds `FabDemoContext` from `(scenarioId, workOrderId)`
   * @param parentSelf     the sharded parent, for journal-bound callbacks
   * @param publisher      demo UX event sink (recovery affordance)
   */
  private class ChildRunHost(
      workOrderId: String,
      freshCtx: Option[FabDemoContext],
      contextFactory: (String, String) => FabDemoContext,
      parentSelf: ActorRef[Command],
      publisher: FabSimulationEvent => Unit
  )(implicit ec: ExecutionContext, scheduler: Scheduler) {

    @volatile private var cachedCtx: FabDemoContext = _

    /**
     * Lazily built once per child incarnation, on first interpreter invocation.
     * Registers a fresh [[PipelineRunRegistry]] generation (fresh start and
     * recovery alike) and guards `stageProgressFn` so intra-stage progress
     * flows through the parent's journal as `StageProgress` events.
     */
    private def ctxFor(runState: FabRunState): FabDemoContext = {
      var c = cachedCtx
      if (c == null) {
        val generation = PipelineRunRegistry.register(workOrderId)
        val base = freshCtx.getOrElse(contextFactory(runState.scenarioId, workOrderId))
        val guarded = base.copy(runToken = () => PipelineRunRegistry.isFresh(workOrderId, generation))
        // P0 (callback guards): superseded runs must not journal or publish.
        guarded.stageProgressFn = (status, detail, phase) =>
          if (guarded.runToken()) parentSelf ! StageProgressEvent(status, detail, phase)
        cachedCtx = guarded
        c = guarded
      }
      c
    }

    private def toFabError(cursor: String, ex: Throwable): FabStageError = ex match {
      case MonarchStageFailedException(merr) =>
        FabStageError(merr.stage, merr.code, merr.errorCode, merr.detail)
      case _ =>
        FabStageError(cursor, None, "UNEXPECTED", Option(ex.getMessage).getOrElse(ex.toString))
    }

    /**
     * Stage interpreter: `FabScenarioPipeline.runStage` with OCAP interception
     * inline.
     *
     * Cursor naming matches `PersistentMonarch` exactly (`stageName#index` over
     * `stages.zipWithIndex`): `completedCount` always equals the number of
     * journaled `StageDone`, so `stageName#completedCount` is the current
     * stage's cursor on fresh runs and on recovery alike.
     *
     * Failure path: any stage failure (classified `StageFailedException` or
     * unexpected) is reported to the parent as `EngineStageFailed` *before*
     * `invokeOcapInterceptor` runs — the same order the old `onStageFailed`
     * hook produced. If OCAP recovers, the returned state is marked
     * `OcapResolved` so the parent journals `OcapHandled` (cursor advances,
     * no success claimed). If OCAP itself throws, the failure propagates and
     * the child journals `RunFailedEvt`.
     */
    def interpreter(stage: PipelineStage, runState: FabRunState): Future[FabRunState] = {
      val fctx = ctxFor(runState)
      // P0 (staleness): a superseded run dies here at the next stage boundary
      // instead of running free with real side effects.
      if (!fctx.runToken()) return Future.failed(StaleRun)
      val cursor = s"${FabPipelineProcessor.stageName(stage)}#${runState.completedCount}"
      if (fctx.runToken()) parentSelf ! EngineStageStarted(cursor)

      // 2026-10-18 (arbitrated): lot notifications are acknowledged. Install a
      // fresh tracking gate for this step; the step only counts as done once
      // every lot notification it sent is confirmed. A lot that never confirms
      // fails the step (LOT_ACK_TIMEOUT) instead of diverging silently.
      val ackGate = LotAckGate.tracking(LotAckTimeout)
      fctx.lotAckGate = ackGate

      FabScenarioPipeline.runStage(stage, runState.state, fctx)
        .recoverWith {
          // Preserve business classification: stage bodies throw the FAB exception
          // type; re-throw it as the engine's type for uniform handling below.
          case FabStageFailedException(err) =>
            Future.failed(MonarchStageFailedException(
              MonarchStageError(err.stageName, err.equipId, err.errorCode, err.detail)))
        }
        .flatMap { next =>
          // Drain the ack gate before the step counts as complete.
          ackGate.seal().map(_ => next).recoverWith {
            case ex =>
              Future.failed(FabStageFailedException(
                FabStageError(cursor, None, "LOT_ACK_TIMEOUT",
                  s"lot notification not confirmed within ${LotAckTimeout.duration.toSeconds}s: ${ex.getMessage}")))
          }
        }
        .map { next =>
          runState.copy(state = next, completedCount = runState.completedCount + 1, lastOutcome = None)
        }
        .recoverWith {
          // StaleRun must propagate untouched — it is the engine's own
          // supersession signal, not a stage failure.
          case NonFatal(ex) if ex ne StaleRun =>
            val fabErr = toFabError(cursor, ex)
            if (fctx.runToken()) parentSelf ! EngineStageFailed(cursor, fabErr)
            FabScenarioPipeline.invokeOcapInterceptor(runState.state, fctx, fabErr).map { recovered =>
              runState.copy(
                state = recovered,
                completedCount = runState.completedCount + 1,
                lastOutcome = Some(FabRunState.OcapResolved(fabErr)))
            }
        }
    }

    /** Child lifecycle → parent journal commands. `onStageFailed` is
      * intentionally not forwarded: the interpreter side-channel already
      * journaled the structured `StageFailed` before OCAP ran. */
    def observer: RunObserver[FabRunState] = new RunObserver[FabRunState] {
      override def onStageComplete(cursor: String, metadata: Map[String, String], snapshot: FabRunState): Unit =
        parentSelf ! EngineStageCompleted(cursor, snapshot)
      override def onRecovery(runId: String, completedCount: Int): Unit =
        parentSelf ! EngineRecovering(completedCount)
      override def onCompleted(runId: String): Unit =
        parentSelf ! EngineRunSucceeded()
      override def onFailed(runId: String, cursor: String, reason: String): Unit =
        parentSelf ! EngineRunFailed(cursor, reason)
    }

    def childBehavior(
        entityId: String,
        stages: Seq[PipelineStage]
    ): Behavior[PersistentMonarch.Command[FabRunState]] =
      PersistentMonarch.behavior[PipelineStage, FabRunState](
        persistenceId = PersistenceId(ChildPersistenceId, entityId),
        stages = stages,
        interpreter = interpreter,
        stateCodec = FabRunStateCodec.protobuf,
        stageName = FabPipelineProcessor.stageName,
        failurePolicy = None, // OCAP interception is inline in the interpreter (see above)
        observer = observer
      )
  }

  // ============================================================
  // Factory
  // ============================================================

  /**
   * Create the parent facade behavior for a FabPipelineExecutionActor.
   *
   * @param entityId          unique entity ID (e.g. workOrderId)
   * @param contextFactory    reconstructs FabDemoContext from (scenarioId, workOrderId) on recovery
   * @param stateFactory      reconstructs FabDemoState from workOrderId on recovery
   *                          (fallback when the domain journal has no snapshot yet)
   * @param stageResolver     resolves scenarioId → Seq[PipelineStage] for recovery
   */
  def apply(
      entityId: String,
      contextFactory: (String, String) => FabDemoContext,
      stateFactory: String => FabDemoState,
      stageResolver: String => Seq[PipelineStage],
      publisher: FabSimulationEvent => Unit = _ => ()
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { ctx =>
      val persistenceId = PersistenceId(EntityKey.name, entityId)
      val startTime = System.currentTimeMillis()

      /** Set while the child is recovering, until the first post-recovery
        * stage completion (or run end) — drives the RECOVERED UX event. */
      var awaitingRecovered = false

      def spawnChild(
          scenarioId: String,
          workOrderId: String,
          stages: Seq[PipelineStage],
          freshCtx: Option[FabDemoContext]
      ): ActorRef[PersistentMonarch.Command[FabRunState]] = {
        val host = new ChildRunHost(workOrderId, freshCtx, contextFactory, ctx.self, publisher)(
          ctx.executionContext, ctx.system.scheduler)
        ctx.spawn(host.childBehavior(entityId, stages), ChildActorName)
      }

      def publishRecovering(workOrderId: String, completedCount: Int): Unit = {
        // RECOVERING/RECOVERED RecoveryEvent + GlobalStatusChanged are demo UX affordance.
        // Published directly via publisher (not journal) — production recovery is transparent.
        publisher(GlobalStatusChanged("RECOVERING", s"Crash recovery for $workOrderId", "Recovery"))
        publisher(RecoveryEvent(
          workOrderId, "RECOVERING",
          eventsReplayed = completedCount,
          phasesSkipped = completedCount,
          recoveryTimeMs = System.currentTimeMillis() - startTime,
          detail = s"Recovering: $completedCount phases completed"))
        awaitingRecovered = true
      }

      def publishRecovered(workOrderId: String, completedCount: Int): Unit = {
        publisher(RecoveryEvent(
          workOrderId, "RECOVERED",
          eventsReplayed = completedCount,
          phasesSkipped = completedCount,
          recoveryTimeMs = System.currentTimeMillis() - startTime,
          detail = s"Recovery succeeded: $completedCount phases skipped, resuming pipeline"))
        awaitingRecovered = false
      }

      val commandHandler: (State, Command) => Effect[Event, State] = { (state, cmd) =>
        (state, cmd) match {

          // ---- Start execution ----
          case (Idle, StartExecution(scenarioId, workOrderId, initialState, stages, fctx0, replyTo)) =>
            val event = Started(scenarioId, workOrderId, stages.size)
            Effect.persist(event).thenRun { _ =>
              val child = spawnChild(scenarioId, workOrderId, stages, Some(fctx0))
              val replyAdapter: ActorRef[PersistentMonarch.RunReply] =
                ctx.messageAdapter[PersistentMonarch.RunReply] {
                  case PersistentMonarch.Accepted(_)      => EngineStartReplied(replyTo, Accepted)
                  case PersistentMonarch.Rejected(_, why) => EngineStartReplied(replyTo, Rejected(why))
                }
              child ! PersistentMonarch.StartRun[FabRunState](
                workOrderId, FabRunState.initial(scenarioId, workOrderId, initialState), replyAdapter)
            }

          // ---- Child's StartRun reply → original replyTo ----
          case (_, EngineStartReplied(replyTo, reply)) =>
            replyTo ! reply
            Effect.none

          // ---- Child engine: stage started ----
          case (_: Executing, EngineStageStarted(cursor)) =>
            ctx.log.info(s"[M3.5] >>> STAGE START: $cursor")
            Effect.persist(StageStarted(cursor, System.currentTimeMillis()))

          // ---- Child engine: stage failed (before OCAP) ----
          case (_: Executing, EngineStageFailed(cursor, error)) =>
            ctx.log.warn(s"[M3.5] Stage FAILED: $cursor — ${error.errorCode}: ${error.detail}")
            Effect.persist(StageFailed(cursor, error, System.currentTimeMillis()))

          // ---- Child engine: stage completed (clean or OCAP-resolved) ----
          case (e: Executing, EngineStageCompleted(cursor, runState)) =>
            ctx.log.info(s"[M3.5] <<< STAGE DONE: $cursor")
            if (awaitingRecovered) publishRecovered(e.workOrderId, e.completedPhases.size)
            runState.lastOutcome match {
              case Some(FabRunState.OcapResolved(err)) =>
                // StageFailed was already journaled via EngineStageFailed; P2: the
                // cursor advances without claiming success.
                Effect.persist(OcapHandled(cursor, err, System.currentTimeMillis(), runState.state))
              case None =>
                Effect.persist(StageCompleted(cursor, System.currentTimeMillis(), Map.empty, Some(runState.state)))
            }

          // ---- Child engine: recovering ----
          case (e: Executing, EngineRecovering(completedCount)) =>
            publishRecovering(e.workOrderId, completedCount)
            Effect.none

          // ---- Child engine: run fully done ----
          case (e: Executing, EngineRunSucceeded()) =>
            if (awaitingRecovered) publishRecovered(e.workOrderId, e.completedPhases.size)
            val wafers = e.fabDemoState.map(_.wafers.values.toSeq).getOrElse(Seq.empty)
            val passCount = wafers.count(w => w.classification.contains("PASS"))
            val scrapCount = wafers.count(w => w.classification.contains("SCRAP"))
            val reworkCount = wafers.count(_.reworkCount > 0)
            Effect.persist(AllCompleted(e.scenarioId, e.workOrderId,
              totalWafers = wafers.size, passedWafers = passCount,
              reworkedWafers = reworkCount, scrappedWafers = scrapCount))

          // ---- Child engine: run failed ----
          case (_: Executing, EngineRunFailed(cursor, reason)) =>
            ctx.log.error(s"[M3.5] PIPELINE FAILED at $cursor: $reason")
            Effect.persist(ExecutionFailed(cursor, reason))

          // ---- Intra-stage progress (replaces ctx.publish(GlobalStatusChanged)) ----
          case (_: Executing, StageProgressEvent(status, detail, phase)) =>
            Effect.persist(StageProgress(status, detail, phase, System.currentTimeMillis()))

          // ---- M3.5: Stop/crash the pipeline ----
          case (es: Executing, StopPipeline(woId)) =>
            Effect.none.thenRun { _ =>
              // Bump the generation NOW (not at recovery): the pre-crash Future chain dies at its
              // next stage boundary instead of running free — with real side effects — for the
              // whole sharding restart-backoff window (~10s). The interpreter checks the
              // PipelineRunRegistry token on every stage entry; the respawned child also
              // invalidates the old engine via its own RunRegistry generation.
              PipelineRunRegistry.register(woId)
              publisher(RecoveryEvent(woId, "CRASH_DETECTED",
                eventsReplayed = es.completedPhases.size, phasesSkipped = es.completedCount,
                recoveryTimeMs = System.currentTimeMillis(),
                detail = s"Actor crash for workOrder $woId (${es.completedCount} phases completed)"))
              ctx.log.warn(s"[FabPipelineExecutionActor:$entityId] Crash injected, stopping actor")
              throw new RuntimeException(s"Pipeline crash injected for workOrder $woId")
            }

          case (Idle, StopPipeline(woId)) =>
            Effect.none.thenRun { _ =>
              PipelineRunRegistry.register(woId)
              publisher(RecoveryEvent(woId, "CRASH_DETECTED", 0, 0, System.currentTimeMillis(),
                s"Actor crash injected (idle state)"))
              ctx.log.warn(s"[FabPipelineExecutionActor:$entityId] Crash injected in Idle state")
              throw new RuntimeException(s"Crash injected for actor $entityId")
            }

          // ---- Ignore late engine callbacks after completion ----
          case (_: Completed, _: EngineStageStarted)   => Effect.none
          case (_: Completed, _: EngineStageFailed)    => Effect.none
          case (_: Completed, _: EngineStageCompleted) => Effect.none
          case (_: Completed, _: EngineRunSucceeded)   => Effect.none
          case (_: Completed, _: EngineRunFailed)      => Effect.none
          case (_: Failed, _: EngineStageStarted)      => Effect.none
          case (_: Failed, _: EngineStageFailed)       => Effect.none
          case (_: Failed, _: EngineStageCompleted)    => Effect.none
          case (_: Failed, _: EngineRunSucceeded)      => Effect.none
          case (_: Failed, _: EngineRunFailed)         => Effect.none
          case (_: Completed, _: StageProgressEvent)   => Effect.none
          case (_: Failed, _: StageProgressEvent)      => Effect.none

          // ---- Already idle/completed/failed, reject new Start ----
          case (s, StartExecution(_, _, _, _, _, replyTo)) =>
            ctx.log.warn(
              s"[FabPipelineExecutionActor:$entityId] Rejecting StartExecution in state ${s.getClass.getSimpleName}"
            )
            replyTo ! Rejected(s"Already in state ${s.getClass.getSimpleName}")
            Effect.none

          case _ =>
            Effect.unhandled
        }
      }

      EventSourcedBehavior[Command, Event, State](
        persistenceId = persistenceId,
        emptyState = Idle,
        commandHandler = commandHandler,
        eventHandler = eventHandler
      ).withTagger(_ => Set(Tag))
        .withRecovery(Recovery.default)
        .snapshotWhen { (_, event, _) =>
          event match {
            case StageCompleted(phase, _, _, _) =>
              phase.startsWith("Measure") || phase == "Classify" || phase == "M35ClassifyWithOcap"
            case _ => false
          }
        }
        .receiveSignal {
          case (execState: Executing, AkkaRecoveryCompleted) =>
            // Crash recovery: respawn the run child — it replays its own journal
            // and resumes the Monarch queue past the journaled cursors.
            // Belt-and-braces: if the crash landed between our Started and the
            // child's RunStarted, re-send StartRun so the run cannot stall; the
            // child Rejects it when it already has a run.
            val stages = stageResolver(execState.scenarioId)
            val child = spawnChild(execState.scenarioId, execState.workOrderId, stages, None)
            val resumeState = execState.fabDemoState.getOrElse(stateFactory(execState.workOrderId))
            child ! PersistentMonarch.StartRun[FabRunState](
              execState.workOrderId,
              FabRunState.initial(execState.scenarioId, execState.workOrderId, resumeState),
              ctx.system.ignoreRef[PersistentMonarch.RunReply])

          case _ => ()
        }
    }
  }

  // ============================================================
  // Event Handler (unchanged)
  // ============================================================

  private val eventHandler: (State, Event) => State = { (state, event) =>
    (state, event) match {
      case (Idle, Started(scenarioId, workOrderId, stageCount)) =>
        Executing(scenarioId, workOrderId, completedPhases = Nil, stageCount = stageCount)

      case (e: Executing, StageStarted(_, _)) =>
        e

      case (e: Executing, StageCompleted(phase, _, _, Some(fabState))) =>
        e.copy(completedPhases = e.completedPhases :+ phase, fabDemoState = Some(fabState))

      case (e: Executing, StageCompleted(phase, _, _, None)) =>
        e.copy(completedPhases = e.completedPhases :+ phase)

      case (e: Executing, StageFailed(_, _, _)) =>
        e

      case (e: Executing, OcapHandled(phase, _, _, fabState)) =>
        e.copy(completedPhases = e.completedPhases :+ phase, fabDemoState = Some(fabState))

      case (e: Executing, StageProgress(_, _, _, _)) =>
        e

      case (e: Executing, AllCompleted(_, _, _, _, _, _)) =>
        Completed(e.scenarioId, e.workOrderId)

      case (_: Executing, ExecutionFailed(phase, reason)) =>
        Failed(phase, reason)

      // Idempotent replay: duplicate events after AllCompleted
      case (_: Completed, StageStarted(_, _)) =>
        state

      case (_: Completed, StageCompleted(_, _, _, _)) =>
        state

      case (_: Completed, StageFailed(_, _, _)) =>
        state

      case (_: Completed, StageProgress(_, _, _, _)) =>
        state

      case _ =>
        state
    }
  }

  // ============================================================
  // Public: convenience factory for sharding registration
  // ============================================================


  def init(
      sharding: ClusterSharding,
      contextFactory: (String, String) => FabDemoContext,
      stateFactory: String => FabDemoState,
      stageResolver: String => Seq[PipelineStage],
      publisher: FabSimulationEvent => Unit = _ => ()
  )(implicit ec: ExecutionContext): Unit = {
    sharding.init(
      Entity(EntityKey) { entityContext =>
        apply(entityContext.entityId, contextFactory, stateFactory, stageResolver, publisher)
      }
    )
  }

  /** Default stage resolver delegates to [[FabScenarioPipeline.resolveStages]]. */
  val DefaultStageResolver: String => Seq[PipelineStage] = FabScenarioPipeline.resolveStages
}
