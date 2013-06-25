package controllers

import scala.util.control.Exception
import edu.knowitall.chunkedextractor.Relnoun
import edu.knowitall.ollie.Ollie
import edu.knowitall.ollie.confidence.OllieConfidenceFunction
import edu.knowitall.srlie.SrlExtractor
import edu.knowitall.srlie.confidence.SrlConfidenceFunction
import edu.knowitall.tool.chunk.OpenNlpChunker
import edu.knowitall.tool.parse.RemoteDependencyParser
import edu.knowitall.tool.segment.Segment
import edu.knowitall.tool.srl.RemoteSrl
import edu.knowitall.tool.stem.MorphaStemmer
import edu.knowitall.tool.parse.graph.DependencyGraph
import edu.knowitall.tool.stem.Lemmatized
import edu.knowitall.tool.chunk.ChunkedToken
import edu.knowitall.chunkedextractor.ReVerb

object Extractors {
  lazy val srlExtractor = new SrlExtractor(clearSrl)
  lazy val srlConf = SrlConfidenceFunction.loadDefaultClassifier()

  lazy val chunker = new OpenNlpChunker()
  lazy val malt = new RemoteDependencyParser("http://trusty.cs.washington.edu:8002") // new MaltParser()
  lazy val clear = new RemoteDependencyParser("http://trusty.cs.washington.edu:8001") // new ClearParser()
  lazy val clearSrl = new RemoteSrl("http://trusty.cs.washington.edu:8011") // new ClearSrl()

  def processSegment(segment: Segment) = {
    def log[T](processor: String, sentence: String, option: Option[T]) = option match {
      case Some(t) => Some(t)
      case None => 
        play.Logger.error("Could not process sentence with " 
          + processor + ": " + sentence); None
    }
    for {
      malt <- malt.synchronized {
        log("malt", segment.text, 
          Exception.catching(classOf[Exception]) opt malt.dependencyGraph(segment.text))
      }
      clear <- clear.synchronized {
        log("clear", segment.text, 
          Exception.catching(classOf[Exception]) opt clear.dependencyGraph(segment.text))
      }
      chunked = chunker.synchronized {
        chunker.chunk(segment.text).toList
      }
      lemmatized = chunked map MorphaStemmer.lemmatizeToken
    } yield Sentence(segment, lemmatized, malt, clear)
  }

  case class Sentence(segment: Segment, chunkedTokens: Seq[Lemmatized[ChunkedToken]], maltGraph: DependencyGraph, clearGraph: DependencyGraph)

  abstract class Extractor {
    def extract(sentence: Sentence): Seq[models.Extraction]
    def apply(sentence: Sentence) = extract(sentence)
  }

  object Ollie extends Extractor {
    import edu.knowitall.openparse.extract.Extraction.{ Part => OlliePart }
    import edu.knowitall.ollie._

    lazy val ollie = new Ollie()
    lazy val ollieConf = OllieConfidenceFunction.loadDefaultClassifier()

    def olliePart(extrPart: OlliePart) = models.Part.create(extrPart.text, extrPart.nodes.map(_.indices))
    def ollieContextPart(extrPart: Context) = {
      models.Part.create(extrPart.text, Iterable(extrPart.interval))
    }

    def extract(sentence: Sentence): Seq[models.Extraction] = {
      val rawOllieExtrs = ollie.extract(sentence.maltGraph).map { extr => (ollieConf(extr), extr) }.toSeq.sortBy(-_._1)
      rawOllieExtrs.map(_._2).map { extr =>
        models.Extraction.fromTriple("Ollie", extr.extr.enabler.orElse(extr.extr.attribution) map ollieContextPart, olliePart(extr.extr.arg1), olliePart(extr.extr.rel), olliePart(extr.extr.arg2), ollieConf(extr))
      }
    }
  }

  object ReVerb extends Extractor {
    import edu.knowitall.chunkedextractor.{ExtractionPart => ChunkedPart}
    def reverbPart(extrPart: ChunkedPart[ChunkedToken]) = models.Part.create(extrPart.text, Some(extrPart.tokenInterval))

    val reverb = new ReVerb()
    def extract(sentence: Sentence) = {
      reverb.extractWithConfidence(sentence.chunkedTokens.map(_.token)).map { case (conf, extr) =>
        models.Extraction.fromTriple("ReVerb", None, ReVerb.reverbPart(extr.extr.arg1), ReVerb.reverbPart(extr.extr.rel), ReVerb.reverbPart(extr.extr.arg2), conf)
      }.toSeq
    }
  }

  object Relnoun extends Extractor {
    import edu.knowitall.chunkedextractor.{ExtractionPart => ChunkedPart}

    lazy val relnoun = new Relnoun()

    def extract(sentence: Sentence) = {
      relnoun.extract(sentence.chunkedTokens).map { extr =>
        models.Extraction.fromTriple("Relnoun", None, ReVerb.reverbPart(extr.extr.arg1), ReVerb.reverbPart(extr.extr.rel), ReVerb.reverbPart(extr.extr.arg2), 0.9)
      }.toSeq
    }
  }

  object OpenIE4 {
    import edu.knowitall.srlie._

    def convert(inst: SrlExtractionInstance): models.Extraction = {
      val arg1 = inst.extr.arg1
      val arg2s: Map[Class[_], Seq[SrlExtraction.Argument]] = inst.extr.arg2s.groupBy(_.getClass)
      val conf = srlConf(inst)

      val vanillaArg2s = arg2s.getOrElse(classOf[SrlExtraction.Argument], Seq.empty)
      val vanillaArg2Parts = vanillaArg2s.map { arg2 =>
        models.Part.create(arg2.text, Seq(arg2.interval))
      }

      val semanticArg2Parts: Seq[models.SemanticPart] = arg2s.filter {
        case (key, value) =>
          key != classOf[SrlExtraction.Argument]
      }.flatMap {
        case (key, values) =>
          values.map { value =>
            val semantics = key match {
              case x if x == classOf[SrlExtraction.LocationArgument] => "spatial"
              case x if x == classOf[SrlExtraction.TemporalArgument] => "temporal"
              case x => throw new IllegalArgumentException("Unknown semantic argument type: " + x)
            }

            val part = models.Part.create(value.text, Seq(value.interval))
            models.SemanticPart(semantics, part)
          }
      }.toSeq

      val attributes = Seq(
        if (inst.extr.passive) Some(models.PassiveAttribute) else None,
        if (inst.extr.active) Some(models.ActiveAttribute) else None,
        if (inst.extr.negated) Some(models.NegativeAttribute) else None).flatten

      val context = {
        inst.extr.context.map { context =>
          val tokens = context.tokens
          val text = context.text
          models.Part.create(text, context.intervals)
        }
      }

      models.Extraction("Open IE 4",
        context = context,
        attributes = attributes,
        arg1 = models.Part.create(arg1.text, Seq(arg1.interval)),
        rel = models.Part.create(inst.extr.relation.text, Seq(inst.extr.relation.span)),
        arg2s = vanillaArg2Parts,
        semanticArgs = semanticArg2Parts,
        conf = conf)

    }

    object Triples extends Extractor {
      val extractorName = "Open IE 4 Triples"
      def extract(sentence: Sentence): Seq[models.Extraction] = {
        val srlExtractions = srlExtractor.synchronized {
          srlExtractor(sentence.clearGraph) flatMap (_.triplize(true))
        }
        ((srlExtractions map convert) ++ Relnoun.extract(sentence)).map(_.copy(extractor = extractorName))
      }
    }

    object Nary extends Extractor {
      val extractorName = "Open IE 4 Nary"
      def extract(sentence: Sentence): Seq[models.Extraction] = {
        val srlExtractions = srlExtractor.synchronized {
          srlExtractor(sentence.clearGraph)
        }
        ((srlExtractions map convert) ++ Relnoun.extract(sentence)).map(_.copy(extractor = extractorName))
      }
    }
  }
}