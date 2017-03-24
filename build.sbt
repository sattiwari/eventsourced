name := "eventsourced"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" % "akka-actor_2.11" % "2.4.17",
  "com.typesafe.akka" %  "akka-remote_2.11" % "2.4.17",
  "commons-io" % "commons-io" % "2.4",
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
  "org.scalatest" % "scalatest_2.11" % "2.2.2"
)

resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"
    