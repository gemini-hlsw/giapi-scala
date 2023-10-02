ThisBuild / tlBaseVersion      := "0.1"
ThisBuild / crossScalaVersions := Seq("2.13.12", "3.3.1")

ThisBuild / tlCiReleaseBranches += "main"

lazy val root = project.in(file(".")).aggregate(giapi).enablePlugins(NoPublishPlugin)

lazy val giapi = project.in(file("modules/giapi")).settings(name := "giapi")
