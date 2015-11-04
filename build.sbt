scalaVersion in ThisBuild := "2.11.7"

name := "akkahttpsample"

organization in ThisBuild := "com.ebay.myorg"

version in ThisBuild := "0.0.1-SNAPSHOT"

resolvers in ThisBuild ++= Seq(
  "eBay Central Releases" at "http://ebaycentral/content/repositories/releases/",
  "eBay Central Snapshots" at "http://ebaycentral/content/repositories/snapshots/",
  "Maven Central" at "http://ebaycentral/content/repositories/central/",
  "SonaType" at "https://oss.sonatype.org/content/repositories/snapshots/"
)

publishArtifact := false

checksums in ThisBuild := Nil

fork in ThisBuild := true

lazy val sample = project



