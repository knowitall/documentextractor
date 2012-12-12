package models

import edu.washington.cs.knowitall.tool.sentence.OpenNlpSentencer

abstract class Input {
  def text: String

  def sentences = {
    Input.sentencer.synchronized {
      Input.sentencer.sentences(text).toList
    }
  }
}

object Input {
  val sentencer = new OpenNlpSentencer
}