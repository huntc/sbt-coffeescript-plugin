sbtPlugin := true

organization := "com.typesafe"

name := "sbt-coffeescript-plugin"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.3"

scalacOptions += "-feature"

libraryDependencies ++= Seq(
  "org.webjars" % "coffee-script" % "1.6.3",
  "org.specs2" %% "specs2" % "2.3.7" % "test",
  "junit" % "junit" % "4.11" % "test"
)

addSbtPlugin("com.typesafe" % "sbt-js-engine" % "1.0.0-SNAPSHOT")

scriptedSettings

scriptedLaunchOpts <+= version apply { v => s"-Dproject.version=$v" }

fork in run := true
