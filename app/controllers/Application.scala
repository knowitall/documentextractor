package controllers

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException

import scala.Array.canBuildFrom
import scala.Option.option2Iterable
import scala.util.control.Exception
import scala.util.control.Exception.catching

import dispatch.Http
import dispatch.as
import dispatch.host
import dispatch.implyRequestHandlerTuple
import edu.knowitall.common.Analysis
import edu.knowitall.common.Resource
import edu.knowitall.tool.coref.StanfordCoreferenceResolver
import edu.knowitall.tool.segment.Segment
import models.Annotation
import models.FileInput
import models.Input
import models.LogEntry
import models.LogInput
import models.TextInput
import models.UrlInput
import play.api.Play.current
import play.api.data.Form
import play.api.data.Forms
import play.api.data.validation.Constraints.nonEmpty
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.Action
import play.api.mvc.Controller
import scalax.chart.Charting.RichCategorizedTuples
import scalax.chart.Charting.XYLineChart

object Application extends Controller {
  final val COREF_ENABLED = false
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
    val annotations = Annotation.findAll(logentryId = id, source = source).sortBy(_.sentence)
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

  def logentrySentences(id: Long) = Action { implicit request =>
    val logentry = LogEntry.find(id)
    Ok(logentry.map(_.sentences.toIndexedSeq.sorted.mkString("\n")).getOrElse("LogEntry not found: " + id))
  }

  private def precisionYield(id: Long, name: String) = {
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

    py
  }

  def logentryPy(id: Long, name: String) = Action { implicit request =>
    val py = precisionYield(id, name)

    val text = py.map { case (extractor, points) =>
      extractor + ":\n" +
        "auc = " + Analysis.areaUnderCurve(points.map { case (conf, p, y) => (p, y) }) + "\n" +
        points.map { case (conf, y, p) => Iterable(conf, y, "%.4f" format p).mkString("\t") }.mkString("\n") + "\n"
    }.mkString("\n", "\n", "\n")

    Ok(text)
  }

  def logentryPyGraph(id: Long, name: String) = Action { implicit request =>
    val py = precisionYield(id, name)

    import scalax.chart._
    import scalax.chart.Charting._
    var files: List[String] = List.empty
    val points = py.map { case (extractor, points) => (extractor, points.map { case (conf, p, y) => (p, y) }) }
    val dataset = points.toXYSeriesCollection()
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
    val py = precisionYield(id, name)

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
      promiseOfString.map(response => Ok(response)).recover { case e: Exception =>
        System.err.println("Could not communicate with summarization host '" + svc.url + "'. Error: " + e.getMessage)
        Ok("Error: could not communicate with server: " + svc.url)
      }
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
    val processedSegments = segments map (segment => segment.copy(text = segment.text.trim)) filter (!_.text.isEmpty) flatMap Extractors.processSegment

    processedSegments map { sentence =>
      val extractions = Extractors.OpenIE4.Nary(sentence) ++ Extractors.ReVerb(sentence)
      extractions.sortBy { extr =>
        (extr.extractor, -extr.confidence, -extr.span.start)
      }

      models.Sentence(sentence.segment, Seq.empty, sentence.maltGraph.nodes.toSeq, extractions)
    }
    // filteredMentions.filter(m => m.mention.offset >= segment.offset && m.mention.offset < segment.offset + segment.text.size)
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
