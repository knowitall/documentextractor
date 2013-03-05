package models

import edu.washington.cs.knowitall.collection.immutable.Interval
import edu.washington.cs.knowitall.tool.coref.CoreferenceResolver
import edu.washington.cs.knowitall.tool.coref.CoreferenceResolver.ResolutionString
import edu.washington.cs.knowitall.tool.coref.Substitution
import edu.washington.cs.knowitall.tool.tokenize.Token

case class Part private (string: String, intervals: Iterable[Interval]) {
  println(string)
  def offsets(tokens: Seq[Token]) = {
    intervals.map(interval => Interval.open(tokens(interval.start).offsets.start, tokens(interval.last).offsets.end))
  }
  def shift(sub: Substitution, shift: Int) = {
    new Substitution(sub.mention.copy(offset = sub.mention.offset + shift), sub.best.copy(offset = sub.best.offset + shift))
  }

  def substitute(string: String, substitutions: Seq[Substitution]) = {
    var text = string
    var adjust = 0
    for (substitution <- substitutions) {
      val Substitution(mention, best) = shift(substitution, adjust)
      text = text.take(mention.offset) + best.text + text.drop(mention.charInterval.end)
      adjust += best.text.size - mention.text.size
    }

    text
  }

  def resolved(s: Sentence): Seq[ResolutionString] = {
    val offsets = this.offsets(s.tokens)
    val mentions = s.mentions map (m => shift(m, -s.segment.offset)) filter (m => offsets exists (o => o superset m.mention.charInterval))

    val resolutions = for {
      offset <- offsets.toSeq
      val substitutions = mentions filter (m => offset superset m.mention.charInterval)
      val text = s.segment.text.substring(offset.start, offset.end)
    } yield {
      CoreferenceResolver.resolve(text, substitutions.map(sub => shift(sub, -offset.start)))
    }

    resolutions.flatten
  }
}
object Part {
  def create(string: String, intervals: Iterable[Interval]) = {
    def collapse(intervals: List[Interval], result: List[Interval]): List[Interval] = intervals match {
      case Nil => result
      case current :: tail => result match {
        case Nil => collapse(tail, List(current))
        case last :: xs if last borders current => collapse(tail, (last union current) :: xs)
        case last :: xs => collapse(tail, current :: last :: xs)
      }
    }

    val sorted = intervals.toList.sorted
    new Part(string, collapse(sorted, List.empty))
  }
}
case class Extraction(extractor: String, context: Option[Part], arg1: Part, rel: Part, arg2: Part, conf: Double) {

}