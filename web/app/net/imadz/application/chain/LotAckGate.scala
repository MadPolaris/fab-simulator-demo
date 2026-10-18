package net.imadz.application.chain

import akka.actor.typed.{ActorRef, Scheduler}
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.util.Timeout
import net.imadz.application.aggregates.LotProtocol.{LotCommand, LotConfirmation}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/**
 * Per-stage acknowledgment gate for lot notifications.
 *
 * Business meaning: when the pipeline tells a lot "record this measurement /
 * classification / split", the lot must confirm it received and journaled the
 * fact before the step counts as done. A lot that crashed or is too slow no
 * longer leaves the two sides silently diverged — the step fails loudly instead,
 * and the failure flows through the normal step-failure path (OCAP evaluation).
 *
 * One gate per step: the step interpreter installs a fresh tracking gate in the
 * context before each step and drains it afterwards. The direct-invocation path
 * (FabDemoService direct step runs, unit tests) keeps the old fire-and-forget
 * behavior via [[LotAckGate.fireAndForget]].
 */
trait LotAckGate {

  /** Send a lot command. Returns immediately; the confirmation is tracked. */
  def tell(lotRef: ActorRef[LotCommand], cmd: ActorRef[LotConfirmation] => LotCommand): Unit

  /** Send a lot command to a sharded lot. Returns immediately; tracked. */
  def tell(lotRef: EntityRef[LotCommand], cmd: ActorRef[LotConfirmation] => LotCommand): Unit

  /** No more notifications will be sent for this step. The returned Future
    * completes once every tracked notification is confirmed, or fails with the
    * first timeout / lot-side error. */
  def seal(): Future[Unit]
}

object LotAckGate {

  /**
   * Fire-and-forget gate — the pre-2026-10-18 behavior. Notifications are sent
   * without tracking; `seal()` always succeeds. Kept for the direct-invocation
   * path where there is no step interpreter to drain the gate.
   */
  def fireAndForget(replyTo: ActorRef[LotConfirmation]): LotAckGate = new LotAckGate {
    override def tell(lotRef: ActorRef[LotCommand], cmd: ActorRef[LotConfirmation] => LotCommand): Unit =
      lotRef ! cmd(replyTo)
    override def tell(lotRef: EntityRef[LotCommand], cmd: ActorRef[LotConfirmation] => LotCommand): Unit =
      lotRef ! cmd(replyTo)
    override def seal(): Future[Unit] = Future.successful(())
  }

  /**
   * Tracking gate — each `tell` sends via ask-with-timeout. `seal()` fails with
   * the first unconfirmed notification (timeout or lot-side error), which the
   * step interpreter turns into a step failure (`LOT_ACK_TIMEOUT`).
   */
  def tracking(timeout: Timeout)(implicit ec: ExecutionContext, scheduler: Scheduler): LotAckGate =
    new LotAckGate {
      private val pending = new AtomicInteger(0)
      private val firstFailure = new AtomicReference[Throwable]()
      private val sealedFlag = new AtomicBoolean(false)
      private val done = Promise[Unit]()

      private def checkDone(): Unit =
        if (sealedFlag.get() && pending.get() == 0) {
          val f = firstFailure.get()
          if (f == null) done.trySuccess(()) else done.tryFailure(f)
        }

      override def tell(lotRef: ActorRef[LotCommand], cmd: ActorRef[LotConfirmation] => LotCommand): Unit = {
        pending.incrementAndGet()
        lotRef.ask[LotConfirmation](replyTo => cmd(replyTo))(timeout, scheduler).onComplete {
          case Success(_) =>
            pending.decrementAndGet()
            checkDone()
          case Failure(ex) =>
            firstFailure.compareAndSet(null, ex)
            pending.decrementAndGet()
            checkDone()
        }
      }

      override def tell(lotRef: EntityRef[LotCommand], cmd: ActorRef[LotConfirmation] => LotCommand): Unit = {
        pending.incrementAndGet()
        implicit val t: Timeout = timeout
        lotRef.ask[LotConfirmation](replyTo => cmd(replyTo)).onComplete {
          case Success(_) =>
            pending.decrementAndGet()
            checkDone()
          case Failure(ex) =>
            firstFailure.compareAndSet(null, ex)
            pending.decrementAndGet()
            checkDone()
        }
      }

      override def seal(): Future[Unit] = {
        sealedFlag.set(true)
        checkDone()
        done.future
      }
    }
}
