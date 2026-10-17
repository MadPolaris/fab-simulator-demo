ThisBuild / organization := "net.imadz"
ThisBuild / organizationName := "MadPolaris"
ThisBuild / organizationHomepage := Some(url("https://github.com/MadPolaris"))
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / version := "0.2"

val akkaVersion = "2.6.20"

ThisBuild / resolvers ++= Seq(
  "Aliyun" at "https://maven.aliyun.com/repository/public/"
)

ThisBuild / scalacOptions ++= Seq("-target:11", "-deprecation", "-feature", "-unchecked", "-Xlint")
ThisBuild / javacOptions ++= Seq("--release", "11")

// fab-simulator-demo — semiconductor fab equipment simulators
// (AMHS, stocker, lithography, CD-SEM, generic equipment).
name := "fab-simulator-demo"

// Standalone demo: never published to Maven Central.
publish / skip := true

enablePlugins(JavaAppPackaging)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "org.scalatest" %% "scalatest" % "3.2.15" % Test,
  "ch.qos.logback" % "logback-classic" % "1.4.5" % Test
)
