package akka.pattern

import akka.testkit.AkkaSpec
import akka.testkit.TestProbe
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor._
import scala.language.postfixOps

object TestActor {
  class StoppingException extends Exception("stopping exception")
  def props(probe: ActorRef) =
    Props(classOf[TestActor], probe)
}

class TestActor(probe: ActorRef) extends Actor {
  import context.dispatcher

  probe ! "STARTED"

  def receive = {
    case "DIE" => context.stop(self)
    case "THROW" => throw new Exception("normal exception")
    case "THROW_STOPPING_EXCEPTION" => throw new TestActor.StoppingException
    case other => probe ! other
  }
}

class TransparentExponentialBackoffSupervisorSpec extends AkkaSpec {

  trait Setup {
    val probe = TestProbe()
    val supervisor = system.actorOf(TransparentExponentialBackoffSupervisor.propsWithDecider(
      TestActor.props(probe.ref),
      200 millis,
      10 seconds,
      0.0) {
        case _: TestActor.StoppingException => SupervisorStrategy.Stop
      })
    probe.expectMsg("STARTED")
  }

  "TransparentExponentialBackoffSupervisor" must {
    "forward messages to child" in new Setup {
      supervisor ! "some message"
      probe.expectMsg("some message")
    }

    "terminate when child terminates" in new Setup {
      probe.watch(supervisor)
      supervisor ! "DIE"
      probe.expectTerminated(supervisor)
    }

    "restart the child with an exponential back off" in new Setup {
      // Exponential back off restart test
      probe.within(1.4 seconds, 2 seconds) {
        supervisor ! "THROW"
        // numRestart = 0 ~ 200 millis
        probe.expectMsg(300 millis, "STARTED")

        supervisor ! "THROW"
        // numRestart = 1 ~ 400 millis
        probe.expectMsg(500 millis, "STARTED")

        supervisor ! "THROW"
        // numRestart = 2 ~ 800 millis
        probe.expectMsg(900 millis , "STARTED")
      }

      // Verify that we only have one child at this point by selecting all the children
      // under the supervisor and broadcasting to them.
      // If there exists more than one child, we will get more than one reply.
      val supervisorChildSelection = system.actorSelection(supervisor.path / "*")
      supervisorChildSelection.tell("testmsg", probe.ref)
      probe.expectMsg("testmsg")
      probe.expectNoMsg
    }

    "stop on exceptions as dictated by the decider" in new Setup {
      probe.watch(supervisor)
      // This should cause the supervisor to stop the child actor and then
      // subsequently stop itself.
      supervisor ! "THROW_STOPPING_EXCEPTION"
      probe.expectTerminated(supervisor)
    }
  }
}
