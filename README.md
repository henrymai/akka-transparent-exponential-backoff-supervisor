## Description
A supervisor that restarts a child actor with an exponential backoff.

This implementation attempts to be as transparent as possible in regards to normally expected supervisor behavior and in terms of interacting with the child actor.

When the child actor throws exceptions, it will be handled via either a specified *akka.actor.SupervisorStrategy.Decider* props parameter or the default *akka.actor.Actor* decider.

As one would expect, if the decider returns the *Restart* directive, the supervisor will restart the child with an exponential back off.

This is different from the official implementation which only restarts the child actor when it is terminated:

http://doc.akka.io/api/akka/2.4.0/index.html#akka.pattern.BackoffSupervisor

https://github.com/akka/akka/blob/v2.4.0/akka-actor-tests/src/test/scala/akka/pattern/BackoffSupervisorSpec.scala

## Integration with your sbt project
You can make your projects depend on this git project.
For example:
```scala
lazy val root = Project("root", file(".")) dependsOn transparentBackoffSupervisorProject
lazy val transparentBackoffSupervisorProject =
  RootProject(uri("https://github.com/henrymai/akka-transparent-exponential-backoff-supervisor.git#master"))
```
For more details: https://www.safaribooksonline.com/library/view/scala-cookbook/9781449340292/ch18s11.html

Then in your code, you can simply:
```scala
import akka.pattern.TransparentExponentialBackoffSupervisor
```

## Example usage
**:paste** the following blocks into into **sbt console** to try it out.

### Sample setup
```scala
import akka.pattern.TransparentExponentialBackoffSupervisor

import akka.actor._
import scala.concurrent.duration._

class TestException(msg: String) extends Exception(msg)

class A extends Actor {
  println("******** Started **********")
  // In this example we use strings as the messages to make sure
  // they get forwarded onto the child. Otherwise the supervisor actor
  // would just eat the Kill or PoisonPill messages.
  def receive = {
    // Kill causes the actor to throw an ActorKilledException.
    // What happens depends on the supervisor decider.
    case "kill" =>
      println("******** Received kill **********")
      self ! Kill
    // PoisonPill actually causes the actor to terminate.
    // The TransparentExponentialBackoffSupervisor should also terminate
    // as if it were the child.
    case "poisonpill" =>
      println("******** Received poisonpill **********")
      self ! PoisonPill
    // Throw an exception; the decider should decide what to do.
    case other =>
      println(s"******** Received: $other ***********")
      throw new TestException(other.toString)
  }
}

val someSystem = ActorSystem()

val supervisorProps =
  TransparentExponentialBackoffSupervisor.propsWithDecider(
    Props(classOf[A]), 1.second, 30.seconds, 0.0) {
    case ex: TestException => akka.actor.SupervisorStrategy.Restart
  }

val supervisor = someSystem.actorOf(supervisorProps, name = "someSupervisor")
```

### Behavior examples
```scala
// The following messages should cause the child to crash and the supervisor
// to apply the exponential back off strategy when respawning it.
supervisor ! "somemsg"

supervisor ! "somemsg2"
```

```scala
// The decider we specified in the sample setup will use the default decider for
// ActorKilledExceptions, which means it will terminate the supervisor.
supervisor ! "kill"
```
Try spamming the messages and observe that the counter increments and that the restart delay increases exponentially.

When the spamming stops, the counter will eventually reset to 0.
