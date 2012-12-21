package models

import java.net.URL

import de.l3s.boilerpipe.extractors.ArticleExtractor

case class UrlInput(urlString: String) extends Input {
  override def text = {
    val url = new URL(urlString)
    ArticleExtractor.INSTANCE.getText(url)
  }
}
