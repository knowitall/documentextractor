package models

import edu.washington.cs.knowitall.tool.coref.CoreferenceResolver
import edu.washington.cs.knowitall.tool.coref.CoreferenceResolver.ResolutionString
import edu.washington.cs.knowitall.tool.coref.Substitution
import edu.washington.cs.knowitall.tool.segment.Segment
import edu.washington.cs.knowitall.tool.tokenize.Token

case class Document(sentences: Seq[Sentence], mentions: Seq[Substitution])
case class Sentence(segment: Segment, mentions: Seq[Substitution], tokens: Seq[Token], extractions: Seq[Extraction]) {
  def resolvedStrings: Seq[ResolutionString] = {
    CoreferenceResolver.resolve(segment.text, mentions)
  }

  def coloredStrings: Seq[AnnotatedString] = {
    def resolve(chars: Seq[(Char, Int)]) = {
      var it = chars
      var result = IndexedSeq.empty[(Char, Int)]

      for (Substitution(mention, best) <- mentions) {
        val skip = mention.offset - segment.offset - it.head._2
        // skip over non-mentions
        result ++= it.take(skip)
        it = it.drop(skip)

        // drop old mention but keep index
        val oldMentionIndex = it.head._2
        it = it.drop(mention.text.size)

        result ++= (mention.text + " ["+best.text+"]").zipWithIndex.map { case (c, i) =>
          val index =
            if (i >= mention.text.size) mention.text.size - 1
            else i
          (c, oldMentionIndex + index)
        }
      }

      result ++ it
    }

    val argOffsets = extractions.flatMap(_.arg1.offsets(tokens)) ++ extractions.flatMap(_.arg2.offsets(tokens))
    val relOffsets = extractions.flatMap(_.rel.offsets(tokens))

    var chars = segment.text.zipWithIndex

    var strings = Vector.empty[AnnotatedString]
    while (!chars.isEmpty) {
      val (plain, rest1) = chars.span { case (c, i) =>
        !argOffsets.exists(offset => i >= offset.start && i < offset.end) &&
        !relOffsets.exists(offset => i >= offset.start && i < offset.end)
      }
      chars = rest1

      if (!plain.isEmpty) {
        strings :+= NormalString(plain.map(_._1).mkString(""))
      }

      val (blue, rest2) = chars.span { case (c, i) =>
        argOffsets.exists(offset => i >= offset.start && i < offset.end)
      }
      if (!blue.isEmpty) {
        strings :+= ColoredString("blue", blue.map(_._1).mkString(""))
      }
      chars = rest2

      val (red, rest3) = chars.span { case (c, i) =>
        relOffsets.exists(offset => i >= offset.start && i < offset.end)
      }
      if (!red.isEmpty) {
        strings :+= ColoredString("red", red.map(_._1).mkString(""))
      }
      chars = rest3
    }

    strings
  }
}

abstract class AnnotatedString(string: String)
case class ColoredString(color: String, string: String) extends AnnotatedString(string)
case class NormalString(string: String) extends AnnotatedString(string)