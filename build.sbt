ThisBuild / organization := "net.imadz"
ThisBuild / organizationName := "MadPolaris"
ThisBuild / organizationHomepage := Some(url("https://github.com/MadPolaris"))
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / version := "0.2"

val akkaVersion = "2.6.20"
val ScalikeJdbcVersion = "3.5.0"
val MongoPluginVersion = "3.0.8"

ThisBuild / resolvers ++= Seq(
  "Aliyun" at "https://maven.aliyun.com/repository/public/"
)

ThisBuild / scalacOptions ++= Seq("-target:11", "-deprecation", "-feature", "-unchecked", "-Xlint")
ThisBuild / javacOptions ++= Seq("--release", "11")

// ------------------------------------------------------------------
// core: 设备仿真器（AMHS/stocker/光刻/CD-SEM）
// ------------------------------------------------------------------
lazy val core = (project in file("core"))
  .settings(
    name := "fab-simulator-core",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "org.scalatest" %% "scalatest" % "3.2.15" % Test,
      "ch.qos.logback" % "logback-classic" % "1.4.5" % Test
    )
  )

// ------------------------------------------------------------------
// web: Play 演示页（fab 仿真 + M3.5 自愈演示）
// ------------------------------------------------------------------
lazy val web = (project in file("web"))
  .enablePlugins(PlayScala)
  .dependsOn(core)
  .settings(
    name := "fab-simulator-web",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "net.imadz" %% "common-core" % "0.2",
      "net.imadz" %% "monarch-core" % "0.2",
      "net.imadz" %% "monarch-persistence" % "0.2",
      "net.imadz" %% "saga-core" % "0.2",
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
      "com.lightbend.akka" %% "akka-projection-eventsourced" % "1.2.5",
      "com.lightbend.akka" %% "akka-projection-core" % "1.2.5",
      "com.github.scullxbones" %% "akka-persistence-mongo-scala" % MongoPluginVersion,
      "org.mongodb.scala" %% "mongo-scala-driver" % "4.3.4",
      "org.scalikejdbc" %% "scalikejdbc" % ScalikeJdbcVersion,
      "org.scalikejdbc" %% "scalikejdbc-config" % ScalikeJdbcVersion,
      "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.14",
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
    ),
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
    ),
    Compile / PB.protoSources += (Compile / sourceDirectory).value / "protobuf"
  )

// Root: aggregate only.
lazy val root = (project in file("."))
  .aggregate(core, web)
  .settings(
    name := "fab-simulator-demo",
    publish / skip := true
  )
