package models

import edu.washington.cs.knowitall.tool.tokenize.Token
import edu.washington.cs.knowitall.collection.immutable.Interval

case class Part(string: String, intervals: Iterable[Interval]) {
  def offsets(tokens: Seq[Token]) = {
    intervals.map(interval => Interval.open(tokens(interval.start).interval.start, tokens(interval.last).interval.end))
  }
}
case class Extraction(extractor: String, context: Option[Part], arg1: Part, rel: Part, arg2: Part, conf: Double)