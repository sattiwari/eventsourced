import sbt._
import Keys._

object Settings {
  val buildOrganization = "com.stlabs"
  val buildVersion      = "0.3-SNAPSHOT"
  val buildScalaVersion = Version.Scala
  val buildSettings = Defaults.defaultSettings ++ Seq(
    organization := buildOrganization,
    version      := buildVersion,
    scalaVersion := buildScalaVersion
  )

  val defaultSettings = buildSettings ++ Seq(
    resolvers ++= Seq(Resolvers.typesafeRepo),
    scalacOptions ++= Seq("-unchecked"),
    parallelExecution in Test := false
  )
}

object Resolvers {
  val typesafeRepo  = "Typesafe Repo"  at "http://repo.typesafe.com/typesafe/releases/"
}

object Dependencies {
  import Dependency._

  val core = Seq(akkaActor, akkaRemote, commonsIo, levelDbJni, scalaTest)
}

object Version {
  val Scala = "2.11.8"
  val Akka  = "2.4.17"
}

object Dependency {
  import Version._

  // -----------------------------------------------
  //  Compile
  // -----------------------------------------------

  val akkaActor   = "com.typesafe.akka"         %  "akka-actor_2.11"    % Akka      % "compile"
  val akkaRemote  = "com.typesafe.akka"         %  "akka-remote_2.11"   % Akka      % "compile"
  val commonsIo   = "commons-io"                %  "commons-io"    % "2.4"     % "compile"
  val levelDbJni  = "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8"     % "compile"

  // -----------------------------------------------
  //  Test
  // -----------------------------------------------

  val scalaTest = "org.scalatest" %% "scalatest" % "2.2.2" % "test"
}

object EventsourcedBuild extends Build {
  import java.io.File._
  import Resolvers._
  import Settings._

  lazy val eventsourced = Project(
    id = "eventsourced",
    base = file("."),
    settings = defaultSettings ++ Seq(
      libraryDependencies ++= Dependencies.core,
      runNobootcpSetting,
      testNobootcpSetting
    )
  )

  val runNobootcp =
    InputKey[Unit]("run-nobootcp", "Runs main classes without Scala library classes on the boot classpath")

  val runNobootcpSetting = runNobootcp <<= inputTask { (argTask: TaskKey[Seq[String]]) => (argTask, streams, fullClasspath in Runtime) map { (at, st, cp) =>
    val runCp = cp.map(_.data).mkString(pathSeparator)
    val runOpts = Seq("-classpath", runCp) ++ at
    val result = Fork.java.fork(None, runOpts, None, Map(), false, LoggedOutput(st.log)).exitValue()
    if (result != 0) error("Run failed")
  } }

  val testNobootcpSetting = test <<= (scalaVersion, streams, fullClasspath in Test) map { (sv, st, cp) =>
    val testCp = cp.map(_.data).mkString(pathSeparator)
    val testExec = "org.scalatest.tools.Runner"
    val testPath = "target/scala-%s/test-classes" format sv
    val testOpts = Seq("-classpath", testCp, testExec, "-p", testPath, "-o")
    val result = Fork.java.fork(None, testOpts, None, Map(), false, LoggedOutput(st.log)).exitValue()
    if (result != 0) error("Tests failed")
  }
}