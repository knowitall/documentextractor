package models

import java.net.URL

import de.l3s.boilerpipe.extractors.ArticleExtractor

case class LogInput(id: Long) extends Input {
  override val sentences = {
    Input.segmentify(LogEntry.find(id = id).map(_.sentences).getOrElse {
      throw new IllegalArgumentException("Could not find log: " + id)
    })
  }
  override def text = {
    sentences.mkString("\n")
  }
}