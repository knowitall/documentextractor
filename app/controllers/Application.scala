package controllers

import java.io.File
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException
import scala.Option.option2Iterable
import scala.annotation.implicitNotFound
import scala.util.control.Exception
import scala.util.control.Exception.catching
import edu.washington.cs.knowitall.chunkedextractor.{ExtractionPart => ChunkedPart}
import edu.washington.cs.knowitall.chunkedextractor.ReVerb
import edu.washington.cs.knowitall.ollie.Context
import edu.washington.cs.knowitall.ollie.Ollie
import edu.washington.cs.knowitall.ollie.confidence.OllieConfidenceFunction
import edu.washington.cs.knowitall.openparse.extract.Extraction.{Part => OlliePart}
import edu.washington.cs.knowitall.tool.chunk.ChunkedToken
import edu.washington.cs.knowitall.tool.chunk.OpenNlpChunker
import edu.washington.cs.knowitall.tool.parse.MaltParser
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
import edu.washington.cs.knowitall.extractor.R2A2
import edu.washington.cs.knowitall.chunkedextractor.Nesty
import edu.washington.cs.knowitall.tool.stem.MorphaStemmer
import edu.washington.cs.knowitall.chunkedextractor.Relnoun
import edu.washington.cs.knowitall.ollie.Attribution
import edu.washington.cs.knowitall.ollie.EnablingCondition
import edu.washington.cs.knowitall.ollie.NaryExtraction

object Application extends Controller {
  val ollie = new Ollie()
  val ollieConf = OllieConfidenceFunction.loadDefaultClassifier()
  val reverb = new ReVerb()
  val nesty = new Nesty()
  val relnoun = new Relnoun()
  val chunker = new OpenNlpChunker()
  val parser = new MaltParser()

  def index = Action {
    Ok(views.html.index(InputForms.textForm, InputForms.urlForm, 'text))
  }

  def logs = Action {
    Ok(views.html.logs(LogEntry.all()))
  }

  def logentry(id: Long) = Action { implicit request =>
    val source = visitorName(request)
    val annotations = Annotation.findAll(logentryId = id, source = source)
    Ok(process(LogInput(id), Some(id), annotations))
  }

  def submitText = Action { implicit request =>
    InputForms.textForm.bindFromRequest.fold(
      errors => BadRequest(views.html.index(errors, InputForms.urlForm, 'text)),
      input => Ok(process(input)))
  }

  def submitFile = Action(parse.multipartFormData) { request =>
    request.body.file("file").map { file =>
      Ok(process(FileInput.process(file.ref.file))(request))
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
      input => Ok(process(input)))
  }

  def process(input: Input, id: Option[Long] = None, annotations: Iterable[Annotation] = Iterable.empty)(implicit request: play.api.mvc.Request[_]) = {
    val sentenceTexts = input.sentences

    val entryId =
      id match {
        case Some(id) => id
        case None => LogEntry.fromRequest(request, sentenceTexts).persist().getOrElse {
          throw new IllegalArgumentException("Could not load entry.")
        }
      }

    views.html.document(buildDocument(sentenceTexts), entryId, annotations)
  }

  def visitorName(request: play.api.mvc.Request[_]) = {
    val remoteIp = request.remoteAddress
    val remoteHost = catching(classOf[UnknownHostException]) opt (InetAddress.getByName(remoteIp).getHostName)
    remoteHost.getOrElse(remoteIp)
  }

  def annotate(logentryId: Long, judgement: Boolean, sentence: String, arg1: String, rel: String, arg2: String) = Action { implicit request =>
    val source = visitorName(request)
    val annotation = new Annotation(logentryId, judgement, source, sentence, arg1, rel, arg2)
    annotation.persist()
    Ok("Annotated")
  }

  def unannotate(logentryId: Long, judgement: Boolean, sentence: String, arg1: String, rel: String, arg2: String) = Action { implicit request =>
    val source = visitorName(request)
    Annotation.delete(logentryId, judgement, source, sentence, arg1, rel, arg2)
    Ok("Unannotated")
  }

  def buildDocument(sentenceTexts: Seq[String]) = {
    val graphs = sentenceTexts map (_.trim) filter (!_.isEmpty) flatMap { sentence =>
      Exception.catching(classOf[Exception]) opt parser.dependencyGraph(sentence) map { (sentence, _) }
    }

    def olliePart(extrPart: OlliePart) = models.Part(extrPart.text, extrPart.nodes.map(_.indices))
    def ollieContextPart(extrPart: Context) = {
      val prefix = extrPart match {
        case _: Attribution => "A: "
        case _: EnablingCondition => "C: "
        case _ => ""
      }
      models.Part(prefix + extrPart.text, Iterable(extrPart.interval))
    }
    def reverbPart(extrPart: ChunkedPart[ChunkedToken]) = models.Part(extrPart.text, Some(extrPart.interval))

    val sentences = graphs map {
      case (text, graph) =>
        val rawOllieExtrs = ollie.extract(graph).map { extr => (ollieConf(extr), extr) }.toSeq.sortBy(-_._1)
        val ollieExtrs = rawOllieExtrs.map(_._2).map { extr =>
          Extraction("Ollie", extr.extr.enabler.orElse(extr.extr.attribution) map ollieContextPart, olliePart(extr.extr.arg1), olliePart(extr.extr.rel), olliePart(extr.extr.arg2), ollieConf(extr))
        }

        val chunked = chunker.chunk(text).toList

        val reverbExtrs = reverb.extractWithConfidence(chunked).toSeq.sortBy(_._1).map {
          case (conf, extr) =>
            Extraction("ReVerb", None, reverbPart(extr.extr.arg1), reverbPart(extr.extr.rel), reverbPart(extr.extr.arg2), conf)
        }

        val lemmatized = chunked map MorphaStemmer.lemmatizeToken

        val nestyExtrs = nesty(lemmatized).map { inst =>
          Extraction("Nesty", Some(models.Part(inst.extr.arg1.text + " " + inst.extr.rel.text, None)), reverbPart(inst.extr.nested.arg1), reverbPart(inst.extr.nested.rel), reverbPart(inst.extr.nested.arg2), 0.0)
        }

        val relnounExtrs = relnoun.extract(lemmatized).map { extr =>
            Extraction("Relnoun", None, reverbPart(extr.extr.arg1), reverbPart(extr.extr.rel), reverbPart(extr.extr.arg2), 0.0)
        }
        
        val naryExtrs = NaryExtraction.from(rawOllieExtrs) map { extr =>
            val suffixText = (extr.suffixes map olliePart).map(_.string).mkString(", ")
            val arg2 = models.Part(suffixText, extr.suffixes.map(_.span))
            Extraction("Nary", extr.enablers.headOption.orElse(extr.attributions.headOption) map ollieContextPart, olliePart(extr.arg1), olliePart(extr.rel), arg2, 0.0)
        }

        val extrs = reverbExtrs ++ ollieExtrs ++ naryExtrs ++ relnounExtrs ++ nestyExtrs

        models.Sentence(text, graph.nodes.toSeq, extrs.toSeq)
    }

    models.Document(sentences)
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
