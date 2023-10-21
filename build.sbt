ThisBuild / tlBaseVersion      := "0.1"
ThisBuild / crossScalaVersions := Seq("2.13.12", "3.3.1")

ThisBuild / tlCiReleaseBranches += "main"

ThisBuild / resolvers += "Gemini Repository".at(
  "https://github.com/gemini-hlsw/maven-repo/raw/master/releases"
)

lazy val root = project.in(file(".")).aggregate(giapi).enablePlugins(NoPublishPlugin)

val catsVersion = "2.10.0"

lazy val giapi = project
  .in(file("modules/giapi"))
  .settings(
    name        := "giapi",
    libraryDependencies ++=
      Seq(
        "org.typelevel"      %%% "cats-core"               % catsVersion,
        "org.typelevel"      %%% "cats-effect"             % "3.5.2",
        "co.fs2"             %%% "fs2-core"                % "3.9.2",
        "org.typelevel"      %%% "mouse"                   % "1.2.1",
        "edu.gemini"         %%% "lucuma-core"             % (if (tlIsScala3.value) "0.88.0" else "0.46.0"),
        "edu.gemini.aspen.gmp" % "gmp-commands-jms-client" % "0.2.7",
        "edu.gemini.aspen"     % "giapi-status-service"    % "0.6.7",
        "edu.gemini.jms"       % "jms-activemq-provider"   % "1.6.7",
        "edu.gemini.aspen.gmp" % "gmp-commands-jms-bridge" % "0.6.7",
        "edu.gemini.aspen.gmp" % "gmp-status-gateway"      % "0.3.7",
        "org.typelevel"      %%% "cats-laws"               % catsVersion % Test,
        "org.typelevel"      %%% "discipline-munit"        % "2.0.0-M3"  % Test,
        "org.typelevel"      %%% "munit-cats-effect"       % "2.0.0-M3"  % Test,
        "edu.gemini.aspen.gmp" % "gmp-statusdb"            % "0.3.7"     % Test,
        "ch.qos.logback"       % "logback-classic"         % "1.4.11"    % Test
      ),
    Test / fork := true
  )
