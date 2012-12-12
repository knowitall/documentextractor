package models

import java.io.File
import edu.washington.cs.knowitall.common.Resource.using
import scala.io.Source
import org.apache.tika.Tika
import de.l3s.boilerpipe.extractors.ArticleExtractor
import play.api.Logger

object FileInput {
  val tika = new Tika()
  def process(file: File) = {
    val (base, dext) = file.getName.span(_ != '.')
    val ext = dext.drop(1)

    val text = if (tika.detect(file) contains "html") {
      Logger.info("Processing file: " + file + " (" + tika.detect(file) + ") as html")
      processHtml(file)
    } else {
      Logger.info("Processing file: " + file + " (" + tika.detect(file) + ")")
      processAny(file)
    }

    TextInput(text)
  }

  private def processHtml(file: File) = {
    ArticleExtractor.INSTANCE.getText(file.toURI().toURL());
  }

  private def processAny(file: File) = {
    val text = tika.synchronized {
      tika.parseToString(file)
    }

    text
  }
}