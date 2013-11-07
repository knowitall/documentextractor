import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "documentextractor"
    val appVersion      = "1.0-SNAPSHOT"

    val nlptoolsVersion = "2.4.4-SNAPSHOT"
    val nlptoolsGroupId = "edu.washington.cs.knowitall.nlptools"

    val appDependencies = Seq(jdbc, anorm,
      "org.apache.tika" % "tika-app" % "1.4",
      nlptoolsGroupId %% "nlptools-parse-malt" % nlptoolsVersion,
      nlptoolsGroupId %% "nlptools-chunk-opennlp" % nlptoolsVersion,
      nlptoolsGroupId %% "nlptools-sentence-opennlp" % nlptoolsVersion,
      nlptoolsGroupId %% "nlptools-coref-stanford" % nlptoolsVersion,
      "edu.washington.cs.knowitall.openie" %% "openie" % "4.1.1",
      "net.databinder.dispatch" %% "dispatch-core" % "0.11.0",
      "de.l3s.boilerpipe" % "boilerpipe" % "1.2.0",
      "net.sourceforge.nekohtml" % "nekohtml" % "1.9.19",
      //"xerces" % "xercesImpl" % "2.9.1",
      "joda-time" % "joda-time" % "2.3",
      "org.apache.derby" % "derby" % "10.10.1.1",
      "org.apache.commons" % "commons-lang3" % "3.1",
      "com.github.wookietreiber" %% "scala-chart" % "0.2.2",
      // logging
      "org.slf4j" % "slf4j-api" % "1.7.5" force(),
      "org.slf4j" % "jul-to-slf4j" % "1.7.5" force(),
      "org.slf4j" % "jcl-over-slf4j" % "1.7.5" force(),
      "org.slf4j" % "slf4j-log4j12" % "1.7.5" force(),
      "ch.qos.logback" % "logback-classic" % "1.0.13" force(),
      "ch.qos.logback" % "logback-core" % "1.0.13" force()
    )

    val main = play.Project(appName, appVersion, appDependencies).settings(
      // Add your own project settings here
      resolvers += "boilerpipe-m2-repo" at "https://boilerpipe.googlecode.com/svn/repo/",
      resolvers += "sonatype-snapshot" at "https://oss.sonatype.org/content/repositories/snapshots/"
    ).settings(net.virtualvoid.sbt.graph.Plugin.graphSettings: _*)
}
