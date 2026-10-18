package net.imadz.application.chain

import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation, RecordWaferMeasured}
import net.imadz.application.chain.FabExecutionModel.{StageError => FabStageError, StageFailedException => FabStageFailedException}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Proves the 2026-10-18 arbitration: lot notifications are acknowledged.
 *
 * Before: the pipeline told the lot "record this" via fire-and-forget
 * (`ignoreLotReply`) and moved on — a crashed or slow lot left the two sides
 * silently diverged.
 *
 * After: each step installs a tracking [[LotAckGate]]; the step only counts as
 * done once every lot notification is confirmed. An unconfirmed notification
 * fails the step as `LOT_ACK_TIMEOUT`, which the pipeline observes through the
 * normal step-failure path (OCAP evaluation / run failure).
 */
class LotAckGateSpec extends AnyWordSpec with Matchers with ScalaFutures {

  implicit private val patience: PatienceConfig = PatienceConfig(10.seconds)

  /** A lot actor that confirms every measurement immediately. */
  private def confirmingLot: Behavior[LotCommand] =
    Behaviors.receiveMessage {
      case RecordWaferMeasured(_, _, replyTo) =>
        replyTo ! LotConfirmation(None)
        Behaviors.same
      case _ => Behaviors.same
    }

  private def withSystem[T](name: String)(body: ActorSystem[Nothing] => T): T = {
    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, name)
    try body(system)
    finally {
      system.terminate()
      system.whenTerminated.futureValue
    }
  }

  "LotAckGate.tracking" should {

    "complete the seal when every notification is confirmed" in withSystem("gate-confirm") { implicit system =>
      implicit val ec = system.executionContext
      implicit val scheduler = system.scheduler
      implicit val timeout: Timeout = 3.seconds

      val gate = LotAckGate.tracking(timeout)
      val lot = system.systemActorOf(confirmingLot, "confirming-lot")
      gate.tell(lot, RecordWaferMeasured(UUID.randomUUID(), 42.0, _))
      gate.tell(lot, RecordWaferMeasured(UUID.randomUUID(), 43.0, _))

      gate.seal().futureValue shouldBe (())
    }

    "fail the seal when the lot never confirms" in withSystem("gate-timeout") { implicit system =>
      implicit val ec = system.executionContext
      implicit val scheduler = system.scheduler
      implicit val timeout: Timeout = 300.millis

      val gate = LotAckGate.tracking(timeout)
      // system.ignoreRef is a black hole: the ask can never complete.
      gate.tell(system.ignoreRef, RecordWaferMeasured(UUID.randomUUID(), 42.0, _))

      val failure = gate.seal().failed.futureValue
      failure shouldBe a[Exception]
    }

    "surface an unconfirmed lot notification to the pipeline as a LOT_ACK_TIMEOUT step failure" in
      withSystem("gate-pipeline") { implicit system =>
        implicit val ec = system.executionContext
        implicit val scheduler = system.scheduler
        implicit val timeout: Timeout = 300.millis

        // Mirrors ChildRunHost.interpreter's drain composition: the step's
        // Future only succeeds after the ack gate drains; a drain failure
        // becomes the step failure the pipeline observes.
        val gate = LotAckGate.tracking(timeout)
        gate.tell(system.ignoreRef, RecordWaferMeasured(UUID.randomUUID(), 1.0, _))

        val stepResult: Future[String] =
          Future.successful("step-done").flatMap { next =>
            gate.seal().map(_ => next).recoverWith {
              case ex =>
                Future.failed(FabStageFailedException(
                  FabStageError("cursor", None, "LOT_ACK_TIMEOUT", ex.getMessage)))
            }
          }

        val err = stepResult.failed.futureValue
        err shouldBe a[FabStageFailedException]
        err.asInstanceOf[FabStageFailedException].error.errorCode shouldBe "LOT_ACK_TIMEOUT"
      }
  }

  "LotAckGate.fireAndForget" should {

    "preserve the old behavior: seal always succeeds without tracking" in withSystem("gate-ff") { implicit system =>
      implicit val ec = system.executionContext
      implicit val scheduler = system.scheduler

      val gate = LotAckGate.fireAndForget(system.ignoreRef)
      gate.tell(system.ignoreRef, RecordWaferMeasured(UUID.randomUUID(), 42.0, _))

      gate.seal().futureValue shouldBe (())
    }
  }
}
