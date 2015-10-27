name := "akka-transparent-exponential-backoff-supervisor"

version := "0.1"

scalaVersion := "2.11.7"

scalacOptions += "-target:jvm-1.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.3.14",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.14",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test"
)
