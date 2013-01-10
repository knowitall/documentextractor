import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "documentextractor"
    val appVersion      = "1.0-SNAPSHOT"

    val appDependencies = Seq(
      "edu.washington.cs.knowitall.ollie" % "ollie-core_2.9.2" % "1.0.1",
      "edu.washington.cs.knowitall.chunkedextractor" % "chunkedextractor_2.9.2" % "1.0.1",
      "org.apache.tika" % "tika-app" % "1.2",
      "edu.washington.cs.knowitall.nlptools" % "nlptools-parse-malt_2.9.2" % "2.3.0",
      "edu.washington.cs.knowitall.nlptools" % "nlptools-chunk-opennlp_2.9.2" % "2.3.0",
      "edu.washington.cs.knowitall.nlptools" % "nlptools-sentence-opennlp_2.9.2" % "2.3.0",
      "net.databinder.dispatch" % "dispatch-core_2.9.2" % "0.9.4",
      "de.l3s.boilerpipe" % "boilerpipe" % "1.2.0",
      "net.sourceforge.nekohtml" % "nekohtml" % "1.9.17",
      "xerces" % "xercesImpl" % "2.9.1",
      "joda-time" % "joda-time" % "2.1",
      "org.apache.derby" % "derby" % "10.9.1.0"
    )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      // Add your own project settings here
      resolvers += "Local Maven Repository" at Path.userHome.asFile.toURI.toURL+"/.m2/repository",
      resolvers += "boilerpipe-m2-repo" at "https://boilerpipe.googlecode.com/svn/repo/"
      // resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"
    )
}
