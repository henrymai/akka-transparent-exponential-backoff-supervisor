package akka.pattern

import akka.actor._
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import scala.concurrent.duration._

object TransparentExponentialBackoffSupervisor {
  private case object RestartChild
  private case class ResetRestartCount(lastNumRestarts: Int)

  def propsWithDecider(
    childProps: Props,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double)(decider: Decider): Props = {
    Props(
      classOf[TransparentExponentialBackoffSupervisor],
      childProps,
      Some(decider),
      minBackoff,
      maxBackoff,
      randomFactor)
  }

  def props(
    childProps: Props,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double): Props = {
    Props(
      classOf[TransparentExponentialBackoffSupervisor],
      childProps,
      None,
      minBackoff,
      maxBackoff,
      randomFactor)
  }
}

class TransparentExponentialBackoffSupervisor(
  props: Props,
  decider: Option[Decider],
  minBackoff: FiniteDuration,
  maxBackoff: FiniteDuration,
  randomFactor: Double)
    extends Actor
    with Stash
    with ActorLogging {

  import TransparentExponentialBackoffSupervisor._
  import context._

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = -1) {
    case ex =>
      val defaultDirective: Directive =
        super.supervisorStrategy.decider.applyOrElse(ex, (_: Any) => Escalate)
      val maybeDirective: Option[Directive] = decider
        .map(_.applyOrElse(ex, (_: Any) => defaultDirective))

      // Get the directive from the specified decider or fallback to
      // the default decider.
      // Whatever the final Directive is, we will translate all Restarts
      // to our own Restarts, which involves stopping the child.
      maybeDirective.getOrElse(defaultDirective) match {
        case Restart =>
          self ! RestartChild
          Stop
        case other => other
      }
  }

  // Initialize by starting up and watching the child
  self ! RestartChild

  def receive = {
    case RestartChild =>
      val childRef = actorOf(props)
      watch(childRef)
      unstashAll()
      become(watching(childRef, 0))
    case _ => stash()
  }

  // Steady state
  def watching(childRef: ActorRef, numRestarts: Int): Receive = {
    case RestartChild =>
      log.debug("Waiting on child termination to restart")
      become(waitingToRestart(childRef, numRestarts))
    case ResetRestartCount(last) =>
      if (last == numRestarts) {
        log.debug(s"Last restart count [$last] matches current count; resetting")
        become(watching(childRef, 0))
      } else {
        log.debug(s"Last restart count [$last] does not match the current count [$numRestarts]")
      }
    case Terminated(`childRef`) =>
      log.debug(s"Terminating, because child [$childRef] terminated itself")
      stop(self)
    case Terminated(other) =>
      log.debug(s"Ignoring unexpected Terminated message from: $other")
    case msg =>
      childRef.forward(msg)
  }

  def calculateDelay(numRestarts: Int): FiniteDuration = {
    // Source:
    // https://github.com/akka/akka/blob/v2.4.0/akka-actor/src/main/scala/akka/pattern/BackoffSupervisor.scala
    // lines 124 - 132
    val rnd = 1.0 + (scala.concurrent.forkjoin.ThreadLocalRandom.current().nextDouble() *
      randomFactor)
    if (numRestarts >= 30) // Duration overflow protection (> 100 years)
      maxBackoff
    else
      maxBackoff.min(minBackoff * math.pow(2, numRestarts)) * rnd match {
        case f: FiniteDuration => f
        case _                 => maxBackoff
      }
  }

  // Waiting to restart the child state
  def waitingToRestart(oldChildRef: ActorRef, numRestarts: Int): Receive = {
    case Terminated(`oldChildRef`) =>
      unwatch(oldChildRef)
      val delay = calculateDelay(numRestarts)
      system.scheduler.scheduleOnce(delay, self, RestartChild)
      log.info(s"Restarting child in: $delay; numRestarts: $numRestarts")
    case Terminated(other) =>
      log.debug(s"Received unexpected Terminated message from: $other")
    case RestartChild =>
      val childRef = actorOf(props)
      watch(childRef)
      unstashAll()
      system.scheduler.scheduleOnce(minBackoff, self, ResetRestartCount(numRestarts + 1))
      become(watching(childRef, numRestarts + 1))
      log.debug(s"Restarted child; numRestarts: $numRestarts")
    case _ =>
      stash()
  }
}
