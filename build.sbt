ThisBuild / tlBaseVersion      := "0.4"
ThisBuild / crossScalaVersions := Seq("3.7.4")

ThisBuild / tlCiReleaseBranches += "gpp"

ThisBuild / resolvers += "Gemini Repository".at(
  "https://github.com/gemini-hlsw/maven-repo/raw/master/releases"
)

lazy val root = project.in(file(".")).aggregate(giapi).enablePlugins(NoPublishPlugin)

val catsVersion = "2.13.0"

lazy val giapi = project
  .in(file("modules/giapi"))
  .settings(
    name        := "giapi",
    libraryDependencies ++=
      Seq(
        "org.typelevel"      %%% "cats-core"               % catsVersion,
        "org.typelevel"      %%% "cats-effect"             % "3.6.3",
        "co.fs2"             %%% "fs2-core"                % "3.12.2",
        "org.typelevel"      %%% "mouse"                   % "1.4.0",
        "edu.gemini"         %%% "lucuma-core"             % "0.171.2",
        "edu.gemini.aspen"     % "giapi-status-service"    % "0.6.7",
        "edu.gemini.jms"       % "jms-activemq-provider"   % "1.6.7",
        "edu.gemini.aspen.gmp" % "gmp-commands-jms-bridge" % "0.6.7",
        "edu.gemini.aspen.gmp" % "gmp-status-gateway"      % "0.3.7",
        "org.typelevel"      %%% "cats-laws"               % catsVersion % Test,
        "org.typelevel"      %%% "discipline-munit"        % "2.0.0"     % Test,
        "org.typelevel"      %%% "munit-cats-effect"       % "2.1.0"     % Test,
        "edu.gemini.aspen.gmp" % "gmp-statusdb"            % "0.3.7"     % Test,
        "ch.qos.logback"       % "logback-classic"         % "1.5.26"    % Test
      ),
    Test / fork := true
  )
