package net.imadz.application.chain

import net.imadz.application.chain.FabExecutionModel._
import net.imadz.domain.routing._
import net.imadz.infrastructure.proto.fab_run_state._
import net.imadz.monarch.persistence.StateCodec

/**
 * `FabRunState` 的 protobuf 编解码（`web/app/protobuf/fab_run_state.proto`）。
 *
 * 遵循 BankStateCodec 的约定："" 编码 None（classification / subLot /
 * spawnedChildLotKey / equipId），字段号是跨版本兼容的唯一依据。
 * 快照只承载 resume 需要的状态——`FabDemoContext`（ActorRef、publisher、
 * awaitPromises）是运行时对象，恢复时由 contextFactory 重建，不进快照。
 */
object FabRunStateCodec {

  val protobuf: StateCodec[FabRunState] = new StateCodec[FabRunState] {
    def encode(s: FabRunState): Array[Byte] = toProto(s).toByteArray
    def decode(bytes: Array[Byte]): FabRunState = fromProto(FabRunStateProto.parseFrom(bytes))
  }

  // ---------- to proto ----------

  private def toProto(s: FabRunState): FabRunStateProto =
    FabRunStateProto(
      scenarioId = s.scenarioId,
      workOrderId = s.workOrderId,
      state = Some(toProtoState(s.state)),
      completedCount = s.completedCount,
      lastOcapError = s.lastOutcome.collect { case FabRunState.OcapResolved(err) => toProtoError(err) }
    )

  private def toProtoState(s: FabDemoState): FabDemoStateProto =
    FabDemoStateProto(
      wafers = s.wafers.view.mapValues(toProtoWafer).toMap,
      passCount = s.passCount,
      scrapCount = s.scrapCount,
      iteration = s.iteration,
      ledgerSeq = s.ledgerSeq,
      pilotPassed = s.pilotPassed,
      reviewApproved = s.reviewApproved,
      spawnedChildLotKey = s.spawnedChildLotKey.getOrElse(""),
      currentRoutingStep = s.currentRoutingStep,
      routingStepReentry = s.routingStepReentry,
      areaVisitHistory = s.areaVisitHistory,
      currentArea = s.currentArea,
      childLotView = s.childLotView.view.mapValues { case (status, n) => ChildLotViewProto(status, n) }.toMap,
      equipmentState = s.equipmentState.view.mapValues(toProtoEquipment).toMap,
      ocapActions = s.ocapActions.map { case (ruleId, plan) => OcapActionEntryProto(ruleId, Some(toProtoPlan(plan))) }
    )

  private def toProtoWafer(w: WaferInfo): WaferInfoProto =
    WaferInfoProto(
      waferId = w.waferId,
      reworkCount = w.reworkCount,
      cdValueHistory = w.cdValueHistory,
      classification = w.classification.getOrElse(""),
      subLot = w.subLot.getOrElse(""),
      measurements = w.measurements.view.mapValues(v => DoubleListProto(v)).toMap
    )

  private def toProtoEquipment(e: EquipmentState): EquipmentStateProto =
    EquipmentStateProto(e.equipmentId, e.status, e.errorCount)

  private def toProtoError(e: StageError): StageErrorProto =
    StageErrorProto(e.stageName, e.equipId.getOrElse(""), e.errorCode, e.detail)

  private def toProtoPlan(p: OcapActionPlan): OcapActionPlanProto = {
    import OcapActionPlanProto.Plan
    val plan = p match {
      case OcapHold(d, r)          => Plan.Hold(OcapHoldProto(d, r))
      case OcapRework(rid, mc)     => Plan.Rework(OcapReworkProto(rid, mc))
      case OcapScrap(r)            => Plan.Scrap(OcapScrapProto(r))
      case OcapNotify(r, e)        => Plan.Notify(OcapNotifyProto(r, e))
      case OcapAdjustRecipe(rid, o) => Plan.AdjustRecipe(OcapAdjustRecipeProto(rid, o))
      case OcapComposite(as)       => Plan.Composite(OcapCompositeProto(as.map(toProtoPlan)))
    }
    OcapActionPlanProto().withPlan(plan)
  }

  // ---------- from proto ----------

  private def fromProto(p: FabRunStateProto): FabRunState =
    FabRunState(
      scenarioId = p.scenarioId,
      workOrderId = p.workOrderId,
      state = fromProtoState(p.getState),
      completedCount = p.completedCount,
      lastOutcome = p.lastOcapError.map(e => FabRunState.OcapResolved(fromProtoError(e)))
    )

  private def fromProtoState(p: FabDemoStateProto): FabDemoState =
    FabDemoState(
      wafers = p.wafers.view.mapValues(fromProtoWafer).toMap,
      passCount = p.passCount,
      scrapCount = p.scrapCount,
      iteration = p.iteration,
      ledgerSeq = p.ledgerSeq,
      pilotPassed = p.pilotPassed,
      reviewApproved = p.reviewApproved,
      spawnedChildLotKey = nonEmpty(p.spawnedChildLotKey),
      currentRoutingStep = p.currentRoutingStep,
      routingStepReentry = p.routingStepReentry.toMap,
      areaVisitHistory = p.areaVisitHistory.toList,
      currentArea = p.currentArea,
      childLotView = p.childLotView.view.mapValues(v => (v.status, v.waferCount)).toMap,
      equipmentState = p.equipmentState.view.mapValues(fromProtoEquipment).toMap,
      ocapActions = p.ocapActions.map(e => (e.ruleId, fromProtoPlan(e.getPlan))).toList
    )

  private def fromProtoWafer(p: WaferInfoProto): WaferInfo =
    WaferInfo(
      waferId = p.waferId,
      reworkCount = p.reworkCount,
      cdValueHistory = p.cdValueHistory.toList,
      classification = nonEmpty(p.classification),
      subLot = nonEmpty(p.subLot),
      measurements = p.measurements.view.mapValues(_.values.toList).toMap
    )

  private def fromProtoEquipment(p: EquipmentStateProto): EquipmentState =
    EquipmentState(p.equipmentId, p.status, p.errorCount)

  private def fromProtoError(p: StageErrorProto): StageError =
    StageError(p.stageName, nonEmpty(p.equipId), p.errorCode, p.detail)

  private def fromProtoPlan(p: OcapActionPlanProto): OcapActionPlan = {
    import OcapActionPlanProto.Plan
    p.plan match {
      case Plan.Hold(h)               => OcapHold(h.durationMs, h.reason)
      case Plan.Rework(r)             => OcapRework(r.recipeId, r.maxCount)
      case Plan.Scrap(s)              => OcapScrap(s.reason)
      case Plan.Notify(n)             => OcapNotify(n.reason, n.escalationPath)
      case Plan.AdjustRecipe(a)       => OcapAdjustRecipe(a.recipeId, a.offsetNm)
      case Plan.Composite(c)          => OcapComposite(c.actions.map(fromProtoPlan).toList)
      case Plan.Empty                 => throw new IllegalArgumentException("OcapActionPlanProto has no plan set")
    }
  }

  private def nonEmpty(s: String): Option[String] = if (s.isEmpty) None else Some(s)
}
