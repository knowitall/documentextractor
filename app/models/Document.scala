package models

import edu.washington.cs.knowitall.tool.tokenize.Token

case class Document(sentences: Seq[Sentence])
case class Sentence(text: String, tokens: Seq[Token], extractions: Seq[Extraction]) {
  def coloredChars = {
    val argOffsets = extractions.flatMap(_.arg1.offsets(tokens)) ++ extractions.flatMap(_.arg2.offsets(tokens))
    val relOffsets = extractions.flatMap(_.rel.offsets(tokens))

    for ((c, i) <- text.zipWithIndex) yield {
      if (c == ' ') {
        NormalChar(c)
      }
      else if (argOffsets.exists(offset => i >= offset.start && i < offset.end)) {
        ColoredChar("blue", c)
      }
      else if (relOffsets.exists(offset => i >= offset.start && i < offset.end)) {
        ColoredChar("red", c)
      }
      else {
        NormalChar(c)
      }
    }
  }
}

abstract class AnnotatedChar(char: Char)
case class ColoredChar(color: String, char: Char) extends AnnotatedChar(char)
case class NormalChar(char: Char) extends AnnotatedChar(char)