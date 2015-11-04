import com.typesafe.sbt.SbtAspectj.AspectjKeys._
import com.typesafe.sbt.SbtAspectj._

val akkaV = "2.3.12"
val rockSqubsV = "0.7.0"
val aspectj = "1.8.5"

aspectjSettings ++ Seq(
  aspectjVersion    :=  aspectj,
  compileOnly in Aspectj    :=  true,
  fork in Test    :=  true,
  javaOptions in Test  <++=  weaverOptions in Aspectj,
  javaOptions in run  <++=  weaverOptions in Aspectj
)

dependencyOverrides ++= Set(
  "org.slf4j" % "slf4j-api" % "1.7.5",
  "org.slf4j" % "slf4j-jdk14" % "1.7.5"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http-experimental" % "1.0",
  "com.typesafe.akka" %% "akka-agent" % akkaV,
  "com.ebay.squbs" %% "rocksqubs-cal" % rockSqubsV,
  "com.typesafe.akka" %% "akka-testkit" % akkaV % "test",
  "org.scalatest" %% "scalatest" % "2.2.1" % "test->*"
)


