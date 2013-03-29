package controllers

import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.File
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException
import scala.Option.option2Iterable
import scala.util.control.Exception
import scala.util.control.Exception.catching
import edu.knowitall.common.Resource
import edu.knowitall.chunkedextractor.{ExtractionPart => ChunkedPart}
import edu.knowitall.chunkedextractor.Nesty
import edu.knowitall.chunkedextractor.ReVerb
import edu.knowitall.chunkedextractor.Relnoun
import edu.knowitall.ollie.Attribution
import edu.knowitall.ollie.Context
import edu.knowitall.ollie.EnablingCondition
import edu.knowitall.ollie.NaryExtraction
import edu.knowitall.ollie.Ollie
import edu.knowitall.ollie.confidence.OllieConfidenceFunction
import edu.knowitall.openparse.extract.Extraction.{Part => OlliePart}
import edu.knowitall.tool.chunk.ChunkedToken
import edu.knowitall.tool.chunk.OpenNlpChunker
import edu.knowitall.tool.parse.RemoteDependencyParser
import edu.knowitall.tool.stem.MorphaStemmer
import models.Annotation
import models.Extraction
import models.FileInput
import models.Input
import models.LogEntry
import models.LogInput
import models.TextInput
import models.UrlInput
import play.api.data.Form
import play.api.data.Forms
import play.api.data.validation.Constraints.nonEmpty
import play.api.mvc.Action
import play.api.mvc.Controller
import edu.knowitall.chunkedextractor.R2A2
import edu.knowitall.chunkedextractor.Nesty
import edu.knowitall.tool.stem.MorphaStemmer
import edu.knowitall.chunkedextractor.Relnoun
import edu.knowitall.ollie.Attribution
import edu.knowitall.ollie.EnablingCondition
import edu.knowitall.ollie.NaryExtraction
import edu.knowitall.tool.segment.Segment
import edu.knowitall.tool.coref.StanfordCoreferenceResolver
import edu.knowitall.collection.immutable.Interval
import edu.knowitall.tool.coref.Substitution
import edu.knowitall.tool.srl.RemoteSrl
import edu.knowitall.tool.srl.ClearSrl
import edu.knowitall.srl.SrlExtractor
import edu.knowitall.srl.confidence.SrlConfidenceFunction
import models.LogInput
import edu.knowitall.common.Analysis
import play.api.libs.concurrent.Execution.Implicits._

object Application extends Controller {
  final val COREF_ENABLED = false

  lazy val ollie = new Ollie()
  lazy val ollieConf = OllieConfidenceFunction.loadDefaultClassifier()

  lazy val srlExtractor = new SrlExtractor(clearSrl)
  lazy val srlConf = SrlConfidenceFunction.loadDefaultClassifier()

  lazy val reverb = new ReVerb()
  lazy val relnoun = new Relnoun()
  lazy val chunker = new OpenNlpChunker()
  lazy val malt = new RemoteDependencyParser("http://rv-n16.cs.washington.edu:8002") // new MaltParser()
  lazy val clear = new RemoteDependencyParser("http://rv-n16.cs.washington.edu:8001") // new ClearParser()
  lazy val clearSrl = new RemoteSrl("http://rv-n16.cs.washington.edu:8011") // new ClearSrl()
  lazy val coref =
    if (COREF_ENABLED) Some(new StanfordCoreferenceResolver())
    else None

  def index = Action {
    Ok(views.html.index(InputForms.textForm, InputForms.urlForm, 'text))
  }

  def logs = Action {
    Ok(views.html.logs(LogEntry.all()))
  }

  def logentryName(id: Long, name: String) = logentry(id, Some(name))
  def logentry(id: Long): play.api.mvc.Action[play.api.mvc.AnyContent] = logentry(id, None)

  def logentry(id: Long, name: Option[String]) = Action { implicit request =>
    val source = visitorName(request, name)
    val annotations = Annotation.findAll(logentryId = id, source = source)
    Ok(process(LogInput(id), source, Some(id), annotations))
  }

  def logentryImport(id: Long, name: String) = Action { implicit request =>
    request.body.asText match {
      case Some(body) =>
        val lines = body.split("\n")
        val annotations = lines.map { line =>
          line.split("\t") match {
            case Array(a, s, arg1, rel, arg2, sentence) if a == "0" || a == "1" =>
              new Annotation(id, a == "1", name, sentence, arg1, rel, arg2)
            case _ => throw new IllegalArgumentException("Malformed import: " + line)
          }
        }

        annotations foreach (_.persist())

        Ok("Imported " + lines.length + " lines.\n")
      case None => Ok("Nothing to import.  Please post data.\n")
    }
  }

  def logentryGold(id: Long, name: String) = Action { implicit request =>
    val source = name
    val annotations = Annotation.findAll(logentryId = id, source = source)
    val builder = new StringBuilder()
    for (annotation <- annotations) {
      val binary = if (annotation.annotation) 1 else 0
      builder.append(
        Iterable(
          binary,
          Iterable(annotation.arg1, annotation.rel, annotation.arg2).mkString("(", "; ", ")"),
          annotation.arg1,
          annotation.rel,
          annotation.arg2,
          annotation.sentence).mkString("\t") + "\n")
    }

    Ok(builder.toString)
  }

  def logentryPy(id: Long, name: String) = Action { implicit request =>
    val annotations = Annotation.findAll(logentryId = id, source = name)
    val sentences = createSentences(LogInput(id).sentences)

    val data = for {
      sent <- sentences
      extr <- sent.extractions
      annotation <- annotations.find(annotation =>
          annotation.sentence == sent.text &&
          annotation.arg1 == extr.arg1.string &&
          annotation.rel == extr.rel.string &&
          annotation.arg2 == extr.arg2.string)
    } yield {
      extr.extractor -> (extr.conf -> annotation.annotation)
    }

    val py = for {(extractor, data) <- data.groupBy(_._1)} yield {
      extractor -> Analysis.precisionYieldMeta(data.map(_._2).sortBy(-_._1).map { case (conf, annotation) => "%.2f".format(conf) -> annotation })
    }

    val text = py.map { case (extractor, points) =>
      extractor + ":\n" + points.map { case (conf, y, p) => Iterable(conf, y, "%.4f" format p).mkString("\t") }.mkString("\n") + "\n"
    }.mkString("\n", "\n", "\n")

    Ok(text)
  }

  def logentryPyGraph(id: Long, name: String) = Action { implicit request =>
    val annotations = Annotation.findAll(logentryId = id, source = name)
    val sentences = createSentences(LogInput(id).sentences)

    val data = for {
      sent <- sentences
      extr <- sent.extractions
      annotation <- annotations.find(annotation =>
          annotation.sentence == sent.text &&
          annotation.arg1 == extr.arg1.string &&
          annotation.rel == extr.rel.string &&
          annotation.arg2 == extr.arg2.string)
    } yield {
      extr.extractor -> (extr.conf -> annotation.annotation)
    }

    val py = for {(extractor, data) <- data.groupBy(_._1)} yield {
      extractor -> Analysis.precisionYieldMeta(data.map(_._2).sortBy(-_._1).map { case (conf, annotation) => "%.2f".format(conf) -> annotation })
    }

    import scalax.chart._
    import scalax.chart.Charting._
    var files: List[String] = List.empty
    val points = py.map { case (extractor, points) => (extractor, points.map { case (conf, p, y) => (p, y) }) }
    val dataset = points.toCategoryTableXYDataset
    val chart = XYLineChart(dataset, title = "Precision - Yield", domainAxisLabel = "Yield", rangeAxisLabel = "Precision")
    val temp = File.createTempFile("temp",".png");

    // save as file and read bytes
    chart.saveAsPNG(temp, (1024,768))
    val bytes = Resource.using(new BufferedInputStream(new FileInputStream(temp))) { bis =>
        Stream.continually(bis.read).takeWhile(-1 !=).map(_.toByte).toArray
      }
    temp.delete()

    Ok(bytes).as("image/png")
  }

  def logentryAnnotations(id: Long, name: String) = Action { implicit request =>
    val annotations = Annotation.findAll(logentryId = id, source = name)
    val sentences = createSentences(LogInput(id).sentences)

    val data = for {
      sent <- sentences
      extr <- sent.extractions
      annotation <- annotations.find(annotation =>
          annotation.sentence == sent.text &&
          annotation.arg1 == extr.arg1.string &&
          annotation.rel == extr.rel.string &&
          annotation.arg2 == extr.arg2.string)
    } yield {
      extr.extractor -> (extr.conf -> annotation.annotation)
    }

    val py = for {(extractor, data) <- data.groupBy(_._1)} yield {
      extractor -> Analysis.precisionYieldMeta(data.map(_._2).sortBy(_._1).map { case (conf, annotation) => "%.2f".format(conf) -> annotation })
    }

    val text = py.map { case (extractor, points) =>
      extractor + ":\n" + points.map { case (conf, y, p) => Iterable(conf, y, "%.4f" format p).mkString("\t") }.mkString("\n") + "\n"
    }.mkString("\n", "\n", "\n")

    Ok(text)
  }

  def submitText = Action { implicit request =>
    InputForms.textForm.bindFromRequest.fold(
      errors => BadRequest(views.html.index(errors, InputForms.urlForm, 'text)),
      input => Ok(process(input, visitorName(request, None))))
  }

  def submitFile = Action(parse.multipartFormData) { request =>
    request.body.file("file").map { file =>
      Ok(process(FileInput.process(file.ref.file), visitorName(request, None))(request))
    }.getOrElse {
      Redirect(routes.Application.index).flashing("error" -> "Missing file")
    }
  }

  def upload = Action(parse.multipartFormData) { request =>
    request.body.file("picture").map { picture =>
      import java.io.File
      val filename = picture.filename
      val contentType = picture.contentType
      picture.ref.moveTo(new File("/tmp/picture"))
      Ok("File uploaded")
    }.getOrElse {
      Redirect(routes.Application.index).flashing(
        "error" -> "Missing file")
    }
  }

  def submitUrl = Action { implicit request =>
    InputForms.urlForm.bindFromRequest.fold(
      errors => BadRequest(views.html.index(InputForms.textForm, errors, 'url)),
      input => Ok(process(input, visitorName(request, None))))
  }

  def summarize = Action { implicit request =>
    import dispatch._
    import play.api.libs.concurrent.Akka
    import play.api.Play.current

    val svc = host("rv-n06.cs.washington.edu", 8081) / "servlet" / "edu.knowitall.testing.SummarizationServletSimple"
    val query = request.body.asFormUrlEncoded.get("query")(0)

    val promiseOfString = Akka.future {
      Http((svc << Map("query" -> query)) OK as.String).apply().split("\n").filter { sentence =>
        !sentence.trim.isEmpty
      }.map { summary =>
        "<li>" + summary + "</li>"
      }.mkString("<ol>", "\n", "</ol>")
    }
    Async {
      promiseOfString.map(response => Ok(response))
    }
  }

  def srl = Action { implicit request =>
    import dispatch._
    import play.api.libs.concurrent.Akka
    import play.api.Play.current

    val svc = host("rv-n07.cs.washington.edu", 8081) / "srl1" / "srlservlet"
    val query = request.body.asFormUrlEncoded.get("query")(0)

    val promiseOfString = Akka.future {
      Http((svc << Map("query" -> query)) OK as.String).apply().split("\n").filter { sentence =>
        !sentence.trim.isEmpty
      }.map { entry =>
        "<li>" + entry + "</li>"
      }.mkString("<ol>", "\n", "</ol>")
    }
    Async {
      promiseOfString.map(response => Ok(response))
    }
  }

  def process(input: Input, source: String, id: Option[Long] = None, annotations: Iterable[Annotation] = Iterable.empty)(implicit request: play.api.mvc.Request[_]) = {
    val segments = input.sentences

    val entryId =
      id match {
        case Some(id) => id
        case None => LogEntry.fromRequest(request, segments.map(_.text)).persist().getOrElse {
          throw new IllegalArgumentException("Could not load entry.")
        }
      }

    views.html.document(buildDocument(segments), entryId, source, annotations)
  }

  def visitorName(request: play.api.mvc.Request[_], name: Option[String]) = {
    name match {
      case Some(name) => name
      case None =>
        val remoteIp = request.remoteAddress
        val remoteHost = catching(classOf[UnknownHostException]) opt (InetAddress.getByName(remoteIp).getHostName)
        remoteHost.getOrElse(remoteIp)
    }
  }

  def annotate(logentryId: Long, judgement: Boolean, source: String, sentence: String, arg1: String, rel: String, arg2: String) = Action { implicit request =>
    val annotation = new Annotation(logentryId, judgement, source, sentence, arg1, rel, arg2)
    annotation.persist()
    Ok("Annotated")
  }

  def unannotate(logentryId: Long, judgement: Boolean, source: String, sentence: String, arg1: String, rel: String, arg2: String) = Action { implicit request =>
    Annotation.delete(logentryId, judgement, source, sentence, arg1, rel, arg2)
    Ok("Unannotated")
  }

  def createSentences(segments: Seq[Segment]) = {
    val sentenceTexts = segments.map(_.text)
    val graphs = segments map (segment => segment.copy(text = segment.text.trim)) filter (!_.text.isEmpty) flatMap { segment =>
      val maltGraph =
        malt.synchronized {
          Exception.catching(classOf[Exception]) opt malt.dependencyGraph(segment.text)
        }
      val clearGraph =
        clear.synchronized {
          Exception.catching(classOf[Exception]) opt clear.dependencyGraph(segment.text)
        }

      for (m <- maltGraph; c <- clearGraph) yield (segment, (m, c))
    }

    def olliePart(extrPart: OlliePart) = models.Part.create(extrPart.text, extrPart.nodes.map(_.indices))
    def ollieContextPart(extrPart: Context) = {
      val prefix = extrPart match {
        case _: Attribution => "A: "
        case _: EnablingCondition => "C: "
        case _ => ""
      }
      models.Part.create(prefix + extrPart.text, Iterable(extrPart.interval))
    }
    def reverbPart(extrPart: ChunkedPart[ChunkedToken]) = models.Part.create(extrPart.text, Some(extrPart.interval))

    graphs map {
      case (segment, (maltGraph, clearGraph)) =>
        val rawOllieExtrs = ollie.extract(maltGraph).map { extr => (ollieConf(extr), extr) }.toSeq.sortBy(-_._1)
        val ollieExtrs = rawOllieExtrs.map(_._2).map { extr =>
          Extraction("Ollie", extr.extr.enabler.orElse(extr.extr.attribution) map ollieContextPart, olliePart(extr.extr.arg1), olliePart(extr.extr.rel), olliePart(extr.extr.arg2), ollieConf(extr))
        }

        val chunked = chunker.synchronized {
          chunker.chunk(segment.text).toList
        }

        val reverbExtrs = reverb.extractWithConfidence(chunked).toSeq.sortBy(_._1).map {
          case (conf, extr) =>
            Extraction("ReVerb", None, reverbPart(extr.extr.arg1), reverbPart(extr.extr.rel), reverbPart(extr.extr.arg2), conf)
        }

        val lemmatized = chunked map MorphaStemmer.lemmatizeToken

        val relnounExtrs = relnoun.extract(lemmatized).map { extr =>
          Extraction("Relnoun", None, reverbPart(extr.extr.arg1), reverbPart(extr.extr.rel), reverbPart(extr.extr.arg2), 0.0)
        }

        val srlExtractions = srlExtractor.synchronized {
          srlExtractor(clearGraph)
        }
        val clearExtrs = srlExtractions.map { inst =>
          val arg1 = inst.extr.arg1
          val arg2 = inst.extr.arg2s.map(_.text).mkString("; ")
          val arg2Interval = if (inst.extr.arg2s.isEmpty) Interval.empty else Interval.span(inst.extr.arg2s.map(_.interval))
          Extraction("Open IE 4", None, models.Part.create(arg1.text, Seq(arg1.interval)), models.Part.create(inst.extr.relation.text, Seq(Interval.span(inst.extr.relation.intervals))), models.Part.create(arg2, Seq(arg2Interval)), 0.0)
        } ++ relnounExtrs.map(_.copy(extractor = "Open IE 4"))

        val clearTriples = srlExtractions.filter(_.extr.arg2s.size > 0).flatMap(_.triplize(true)).map { inst =>
          val conf = srlConf(inst)
          val arg1 = inst.extr.arg1
          val arg2 = inst.extr.arg2s.map(_.text).mkString("; ")
          val arg2Interval = if (inst.extr.arg2s.isEmpty) Interval.empty else Interval.span(inst.extr.arg2s.map(_.interval))
          Extraction("Open IE 4 Triples", None, models.Part.create(arg1.text, Seq(arg1.interval)), models.Part.create(inst.extr.relation.text, Seq(Interval.span(inst.extr.relation.intervals))), models.Part.create(arg2, Seq(arg2Interval)), conf)
        } ++ relnounExtrs.map(_.copy(extractor = "Open IE 4 Triples"))

        val extrs = (reverbExtrs ++ ollieExtrs ++ relnounExtrs ++ clearExtrs ++ clearTriples).toSeq.sortBy { extr =>
          (extr.extractor, -extr.confidence, -extr.span.start)
        }

        // filteredMentions.filter(m => m.mention.offset >= segment.offset && m.mention.offset < segment.offset + segment.text.size)
        models.Sentence(segment, Seq.empty, maltGraph.nodes.toSeq, extrs.toSeq)
    }
  }

  def buildDocument(segments: Seq[Segment]) = {
    val sentences = createSentences(segments)

    /*
    val mentions = coref.map(_.substitutions(sentences.map(_.).mkString("\n"))).getOrElse(List.empty)

    val filteredMentions = mentions.filter {
      case s @ Substitution(mention, best) =>
        !(mentions exists (sub => sub != s && (sub.mention.charInterval intersects mention.charInterval)))
    }.filter {
      case s @ Substitution(mention, best) =>
        best.offset < mention.offset
    }
    */

    models.Document(sentences, Seq.empty)
  }

  object InputForms {
    def urlForm: Form[UrlInput] = {
      def unapply(url: String): Option[String] = {
        Some(url)
      }
      Form(
        (Forms.mapping(
          "url" -> Forms.text.verifying("Malformed URL.", Exception.catching(classOf[MalformedURLException]) opt new URL(_) isDefined)))(UrlInput.apply)(UrlInput.unapply))
    }

    def textForm: Form[TextInput] = {
      def unapply(url: String): Option[String] = {
        Some(url)
      }
      Form(
        (Forms.mapping(
          "text" -> Forms.text.verifying(nonEmpty)))(TextInput.apply)(TextInput.unapply))
    }
  }
}
