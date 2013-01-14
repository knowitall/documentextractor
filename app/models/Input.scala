package models

import edu.washington.cs.knowitall.tool.sentence.OpenNlpSentencer
import edu.washington.cs.knowitall.tool.segment.Segment

abstract class Input {
  def text: String

  def sentences: Seq[Segment] = {
    Input.sentencer.synchronized {
      Input.segmentify(Input.sentencer.segmentTexts(text).toList.map(_.replaceAll("\n", " ")))
    }
  }
}

object Input {
  val sentencer = new OpenNlpSentencer
  def segmentify(sentences: Seq[String]): Seq[Segment] = {
    var offset: Int = 0
    for (sentence <- sentences) yield {
      val result = Segment(sentence, offset)
      offset += sentence.length() + 1

      result
    }
  }
}
