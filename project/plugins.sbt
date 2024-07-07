ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

addSbtPlugin("com.typesafe"      % "sbt-mima-plugin" % "1.1.3")
addSbtPlugin("com.github.sbt"    % "sbt-ci-release"  % "1.5.12")
addSbtPlugin("org.typelevel"     % "sbt-tpolecat"    % "0.5.1")
addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "5.10.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"    % "2.5.2")
addSbtPlugin("com.47deg"         % "sbt-microsites"  % "1.4.4")
addSbtPlugin("org.scalameta"     % "sbt-mdoc"        % "2.5.3")
addSbtPlugin("com.github.sbt"    % "sbt-site"        % "1.7.0")
addSbtPlugin("com.github.sbt"    % "sbt-unidoc"      % "0.5.0")
