lazy val publishSettings = Seq(
  publish / skip := false,
  gitPublishDir := file("/src/maven-repo"),
  licenses += ("LGPL-2.1", url("https://opensource.org/licenses/LGPL-2.1")),
)

lazy val mariadbConnector = project.in(file("."))
  .settings(publishSettings)
  .settings(
    scalaVersion := "2.13.7",
    name := "mariadb-java-client",
    organization := "org.mariadb.jdbc",
    version := "2.7.4-BL2",
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.2.3" % "test,optional",
      "com.amazonaws" % "aws-java-sdk-rds" % "1.11.734" % "optional",
      "com.github.waffle" % "waffle-jna" % "2.2.1" % "optional",
      "io.circe" %% "circe-core" % "0.14.1",
      "junit" % "junit" % "4.13.1" % "test",
      "net.java.dev.jna" % "jna" % "5.5.0" % "optional",
      "net.java.dev.jna" % "jna-platform" % "5.5.0" % "optional",
      "org.osgi" % "org.osgi.core" % "6.0.0" % "provided",
      "org.osgi" % "org.osgi.compendium" % "5.0.0" % "provided",
      "org.slf4j" % "slf4j-api" % "[1.4.0,1.7.25]" % "optional",
    ),
  )
