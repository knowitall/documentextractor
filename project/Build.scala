import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "documentextractor"
    val appVersion      = "1.0-SNAPSHOT"

    val nlptoolsVersion = "2.4.2"
    val nlptoolsGroupId = "edu.washington.cs.knowitall.nlptools"

    val appDependencies = Seq(jdbc, anorm,
      "edu.washington.cs.knowitall.ollie" %% "ollie-core" % "1.0.3",
      "edu.washington.cs.knowitall.chunkedextractor" %% "chunkedextractor" % "1.0.4",
      "org.apache.tika" % "tika-app" % "1.2",
      nlptoolsGroupId %% "nlptools-parse-malt" % nlptoolsVersion,
      nlptoolsGroupId %% "nlptools-chunk-opennlp" % nlptoolsVersion,
      nlptoolsGroupId %% "nlptools-sentence-opennlp" % nlptoolsVersion,
      nlptoolsGroupId %% "nlptools-coref-stanford" % nlptoolsVersion,
      "org.scalaz" %% "scalaz" % "6.0.4",
      "edu.washington.cs.knowitall.srlie" %% "openie-srl" % "1.0.0-RC1",
      "net.databinder.dispatch" %% "dispatch-core" % "0.10.1",
      "de.l3s.boilerpipe" % "boilerpipe" % "1.2.0",
      "net.sourceforge.nekohtml" % "nekohtml" % "1.9.18",
      "xerces" % "xercesImpl" % "2.9.1",
      "joda-time" % "joda-time" % "2.2",
      "org.apache.derby" % "derby" % "10.9.1.0",
      "org.apache.commons" % "commons-lang3" % "3.1",
      "com.github.wookietreiber" %% "scala-chart" % "latest.integration"
    )

    val main = play.Project(appName, appVersion, appDependencies).settings(
      // Add your own project settings here
      resolvers += "boilerpipe-m2-repo" at "https://boilerpipe.googlecode.com/svn/repo/",
      resolvers += "sonatype-snapshot" at "https://oss.sonatype.org/content/repositories/snapshots/"
    )
}
