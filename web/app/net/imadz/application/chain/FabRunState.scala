package net.imadz.application.chain

import net.imadz.application.chain.FabExecutionModel.{FabDemoState, StageError}

/**
 * Run state `S` for the monarch-persistence child actor
 * (`PersistentMonarch[PipelineStage, FabRunState]`, spawned by
 * [[FabPipelineExecutionActor]]).
 *
 * The child's journal stores snapshots of this (protobuf-encoded) in `StageDone`
 * events; on crash recovery the last snapshot is decoded and the Monarch queue
 * resumes from it. `completedCount` doubles as the `#index` cursor suffix, so
 * the interpreter can name cursors exactly the way `PersistentMonarch` does
 * (`stages.zipWithIndex`).
 */
final case class FabRunState(
    scenarioId: String,
    workOrderId: String,
    state: FabDemoState,
    /** Position of the next stage to run. Advanced by the interpreter on every
      * returned state, so it always equals the number of journaled `StageDone`. */
    completedCount: Int,
    /** How the most recent stage finished. `Some(OcapResolved)` means the stage
      * failed but OCAP recovered it — the cursor still advances (no clean
      * completion claimed), mirroring the old `OcapHandled` event semantics. */
    lastOutcome: Option[FabRunState.StageOutcome]
)

object FabRunState {

  sealed trait StageOutcome

  /** The stage failed with `error` and `invokeOcapInterceptor` recovered it. */
  final case class OcapResolved(error: StageError) extends StageOutcome

  def initial(scenarioId: String, workOrderId: String, state: FabDemoState): FabRunState =
    FabRunState(scenarioId, workOrderId, state, completedCount = 0, lastOutcome = None)
}
