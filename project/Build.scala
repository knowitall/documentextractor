import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "documentextractor"
    val appVersion      = "1.0-SNAPSHOT"

    val nlptoolsVersion = "2.4.0"
    val nlptoolsGroupId = "edu.washington.cs.knowitall.nlptools"

    val appDependencies = Seq(
      "edu.washington.cs.knowitall.ollie" % "ollie-core_2.9.2" % "1.0.2",
      "edu.washington.cs.knowitall.chunkedextractor" % "chunkedextractor_2.9.2" % "1.0.2",
      "org.apache.tika" % "tika-app" % "1.2",
      "org.scalaz" % "scalaz_2.9.2" % "6.0.4",
      nlptoolsGroupId % "nlptools-parse-malt_2.9.2" % nlptoolsVersion,
      nlptoolsGroupId % "nlptools-chunk-opennlp_2.9.2" % nlptoolsVersion,
      nlptoolsGroupId % "nlptools-sentence-opennlp_2.9.2" % nlptoolsVersion,
      nlptoolsGroupId % "nlptools-coref-stanford_2.9.2" % nlptoolsVersion,
      "edu.washington.cs.knowitall.openiesrl" % "openie-srl_2.9.2" % "1.0-SNAPSHOT",
      "net.databinder.dispatch" % "dispatch-core_2.9.2" % "0.9.5",
      "de.l3s.boilerpipe" % "boilerpipe" % "1.2.0",
      "net.sourceforge.nekohtml" % "nekohtml" % "1.9.18",
      "xerces" % "xercesImpl" % "2.9.1",
      "joda-time" % "joda-time" % "2.2",
      "org.apache.derby" % "derby" % "10.9.1.0",
      "org.apache.commons" % "commons-lang3" % "3.1"
    )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      // Add your own project settings here
      resolvers += "boilerpipe-m2-repo" at "https://boilerpipe.googlecode.com/svn/repo/",
      resolvers += "sonatype-snapshot" at "https://oss.sonatype.org/content/repositories/snapshots/"
    )
}
