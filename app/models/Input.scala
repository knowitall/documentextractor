package models

import edu.washington.cs.knowitall.tool.sentence.OpenNlpSentencer

abstract class Input {
  def text: String

  def sentences: Seq[String] = {
    Input.sentencer.synchronized {
      Input.sentencer.segmentTexts(text).toList.map(_.replaceAll("\n", " "))
    }
  }
}

object Input {
  val sentencer = new OpenNlpSentencer
}
