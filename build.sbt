import sbt.Keys._
import sbt._
import net.virtualvoid.sbt.graph._
import com.typesafe.sbt.packager.SettingsHelper
import gov.nasa.jpl.imce.sbt._

updateOptions := updateOptions.value.withCachedResolution(true)

val resourceArtifact = settingKey[Artifact]("Specifies the project's resource artifact")

val Scripts = config("scripts")

def IMCEThirdPartyProject(projectName: String, location: String): Project =
  Project(projectName, file("."))
    .enablePlugins(IMCEGitPlugin)
    .enablePlugins(IMCEReleasePlugin)
    .settings(IMCEReleasePlugin.packageReleaseProcessSettings)
    .configs(Scripts)
    .settings(
      IMCEKeys.targetJDK := IMCEKeys.jdk18.value,
      IMCEKeys.licenseYearOrRange := "2015-2016",
      IMCEKeys.organizationInfo := IMCEPlugin.Organizations.thirdParty,
      git.baseVersion := Versions.version,
      scalaVersion := Versions.scala_version,
      projectID := {
        val previous = projectID.value
        previous.extra(
          "build.date.utc" -> buildUTCDate.value,
          "artifact.kind" -> "third_party.aggregate.libraries")
      }
    )
    .settings(SettingsHelper.makeDeploymentSettings(Scripts, packageBin in Scripts, "zip"))
    .settings(

      // disable publishing the main jar produced by `package`
      publishArtifact in(Compile, packageBin) := false,

      // disable publishing the main API jar
      publishArtifact in(Compile, packageDoc) := false,

      // disable publishing the main sources jar
      publishArtifact in(Compile, packageSrc) := false,

      // disable publishing the jar produced by `test:package`
      publishArtifact in(Test, packageBin) := false,

      // disable publishing the test API jar
      publishArtifact in(Test, packageDoc) := false,

      // disable publishing the test sources jar
      publishArtifact in(Test, packageSrc) := false,

      // name the '*-resource.zip' in the same way as other artifacts
      com.typesafe.sbt.packager.Keys.packageName in Universal :=
        normalizedName.value + "_" + scalaBinaryVersion.value + "-" + version.value,

      resourceArtifact := Artifact((name in Universal).value, "zip", "zip", Some("resource"), Seq(), None, Map()),

      artifacts += resourceArtifact.value,

      // contents of the '*-resource.zip' to be produced by 'universal:packageBin'
      mappings in Universal ++= {
        val appC = appConfiguration.value
        val cpT = classpathTypes.value
        val up = update.value
        val s = streams.value

        def getFileIfExists(f: File, where: String)
        : Option[(File, String)] =
          if (f.exists()) Some((f, s"$where/${f.getName}")) else None

        val libDir = location + "/lib/"
        val srcDir = location + "/lib.sources/"
        val docDir = location + "/lib.javadoc/"

        s.log.info(s"====== $projectName (universal) =====")

        val compileConfig: ConfigurationReport = {
          up.configurations.find((c: ConfigurationReport) => Configurations.Compile.name == c.configuration).get
        }

        def transitiveScope(modules: Set[Module], g: ModuleGraph): Set[Module] = {

          @annotation.tailrec
          def acc(focus: Set[Module], result: Set[Module]): Set[Module] = {
            val next = g.edges.flatMap { case (fID, tID) =>
              focus.find(m => m.id == fID).flatMap { _ =>
                g.nodes.find(m => m.id == tID)
              }
            }.to[Set]
            if (next.isEmpty)
              result
            else
              acc(next, result ++ next)
          }

          acc(modules, Set())
        }

        val zipFiles: Set[File] = {
          val jars = for {
            oReport <- compileConfig.details
            mReport <- oReport.modules
            (artifact, file) <- mReport.artifacts
            if "zip" == artifact.extension
            file <- {
              s.log.info(s"compile: ${oReport.organization}, ${file.name}")
              val graph = backend.SbtUpdateReport.fromConfigurationReport(compileConfig, mReport.module)
              val roots: Set[Module] = graph.nodes.filter { m =>
                m.id.organisation == mReport.module.organization &&
                  m.id.name == mReport.module.name &&
                  m.id.version == mReport.module.revision
              }.to[Set]
              val scope: Seq[Module] = transitiveScope(roots, graph).to[Seq].sortBy(m => m.id.organisation + m.id.name)

              val files = scope.flatMap { m: Module => m.jarFile }.to[Seq].sorted
              s.log.info(s"Excluding ${files.size} jars from zip aggregate resource dependencies")
              require(
                files.nonEmpty,
                s"There should be some excluded dependencies\ngraph=$graph\nroots=$roots\nscope=$scope")
              files.foreach { f =>
                s.log.info(s" exclude: ${f.getParentFile.getParentFile.name}/${f.getParentFile.name}/${f.name}")
              }
              files
            }
          } yield file
          jars.to[Set]
        }

        val fileArtifacts: Seq[(String, String, File, Artifact)] = for {
          oReport <- compileConfig.details
          organizationArtifactKey = s"{oReport.organization},${oReport.name}"
          mReport <- oReport.modules
          (artifact, file) <- mReport.artifacts
          if !mReport.evicted && "jar" == artifact.extension && !zipFiles.contains(file)
        } yield (oReport.organization, oReport.name, file, artifact)

        val fileArtifactsByType: Map[String, Seq[(String, String, File, Artifact)]] =
          fileArtifacts.groupBy { case (_, _, _, a) => a.`classifier`.getOrElse(a.`type`) }

        val jarArtifacts = fileArtifactsByType
          .getOrElse("jar", Seq.empty)
          .map { case (o, _, jar, _) => o -> jar }
          .to[Set].to[Seq].sortBy { case (o, jar) => s"$o/${jar.name}" }
        val srcArtifacts = fileArtifactsByType
          .getOrElse("sources", Seq.empty)
          .map { case (o, _, jar, _) => o -> jar }
          .to[Set].to[Seq].sortBy { case (o, jar) => s"$o/${jar.name}" }
        val docArtifacts = fileArtifactsByType
          .getOrElse("javadoc", Seq.empty)
          .map { case (o, _, jar, _) => o -> jar }
          .to[Set].to[Seq].sortBy { case (o, jar) => s"$o/${jar.name}" }

        val jars = jarArtifacts.map { case (o, jar) =>
          s.log.info(s"* jar: $o/${jar.name}")
          jar -> (libDir + jar.name)
        }
        val srcs = srcArtifacts.map { case (o, jar) =>
          s.log.info(s"* src: $o/${jar.name}")
          jar -> (srcDir + jar.name)
        }
        val docs = docArtifacts.map { case (o, jar) =>
          s.log.info(s"* doc: $o/${jar.name}")
          jar -> (docDir + jar.name)
        }

        jars ++ srcs ++ docs
      },

      mappings in Scripts := {

        val slog = streams.value.log

        slog.info(s"====== $projectName (scripts) =====")

        val tfilter: DependencyFilter = new DependencyFilter {
          def apply(c: String, m: ModuleID, a: Artifact): Boolean =
            a.extension == "tar.gz" &&
              m.organization.startsWith("org.apache.jena") &&
              m.name.startsWith("apache-jena-fuseki")
        }
        update.value
          .matching(tfilter)
          .headOption
          .fold[Seq[(File, String)]] {
          slog.error("Cannot find apache-jena-fuseki tar.gz!")
          Seq.empty[(File, String)]
        } { tgz =>
          slog.warn(s"found: $tgz")
          val dir = target.value / "tarball"
          IO.createDirectory(dir)
          Process(Seq("tar", "--strip-components", "1", "-zxf", tgz.getAbsolutePath), Some(dir)).! match {
            case 0 => ()
            case n => sys.error("Error extracting " + tgz + ". Exit code: " + n)
          }

          val s1 = (dir / "bin").*** pair relativeTo(dir)
          val s2 = (dir ** "fuseki-server") pair relativeTo(dir)
          val s3 = (dir ** "fuseki-server.jar") pair relativeTo(dir)
          s1 ++ s2 ++ s3
        }

      },

      extractArchives := {},

      name in Scripts := (name in Universal).value,

      artifacts +=
        Artifact((name in Universal).value, "zip", "zip", "resource"),

      artifacts +=
        Artifact((name in Scripts).value, "zip", "zip", "scripts"),

      packagedArtifacts +=
        Artifact((name in Universal).value, "zip", "zip", "resource") -> (packageBin in Universal).value,

      packagedArtifacts +=
        Artifact((name in Scripts).value, "zip", "zip", "scripts") -> (packageBin in Scripts).value,

      packageBin in Scripts := {
        val fileMappings = (mappings in Scripts).value
        val output = target.value / s"${packageName.value}_scripts.zip"
        IO.zip(fileMappings, output)
        output
      }

    )

lazy val jenaLibs = IMCEThirdPartyProject("jena-libraries", "jenaLibs")
  .settings(
    resolvers += Resolver.bintrayRepo("jpl-imce", "gov.nasa.jpl.imce"),

    libraryDependencies ++= Seq(
      "gov.nasa.jpl.imce" %% "imce.third_party.other_scala_libraries" % Versions_other_scala_libraries.version
        % "compile"
        artifacts
        Artifact("imce.third_party.other_scala_libraries", "zip", "zip", Some("resource"), Seq(), None, Map()),

      "org.apache.jena" % "apache-jena-fuseki" % Versions.jenaFuseki
        % "compile"
        artifacts
        Artifact("apache-jena-fuseki", "tar.gz", "tar.gz"),

      "org.apache.jena" % "jena-tdb" % Versions.jenaTDB %
      "compile" withSources() withJavadoc(),

      "org.apache.jena" % "jena-fuseki-server" % Versions.jenaFuseki %
      "compile" withSources()),

    // Exclusions to avoid bringing indirect dependencies that conflict with MD 18.0's libraries
    libraryDependencies ~= {
      _ map {
        case m if m.organization == "org.apache.jena" =>
          m.
            exclude("commons-codec", "commons-codec").
            exclude("commons-logging", "commons-logging").
            exclude("org.slf4j", "slf4j-api").
            exclude("org.slf4j", "slf4j-nop").
            exclude("org.slf4j", "jcl-over-slf4j").
            exclude("ch.qos.logback", "logback-classic")
        case m => m
      }
    })