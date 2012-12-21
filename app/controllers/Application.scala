package controllers

import edu.washington.cs.knowitall.ollie.Ollie
import models.{ FileInput, Input, TextInput, UrlInput }
import play.api._
import play.api.data.{ Form, Forms }
import play.api.mvc._
import edu.washington.cs.knowitall.tool.parse.MaltParser
import edu.washington.cs.knowitall.common.Resource.using
import edu.washington.cs.knowitall.ollie.confidence.OllieIndependentConfFunction
import models.Extraction
import scala.util.control.Exception
import scala.io.Source
import play.api.data.validation.Constraints._
import java.net.URL
import java.net.MalformedURLException
import edu.washington.cs.knowitall.openparse.extract.Extraction.{ Part => OlliePart }
import models.LogEntry
import models.LogInput
import edu.washington.cs.knowitall.extractor.ReVerbExtractor
import edu.washington.cs.knowitall.extractor.conf.ReVerbOpenNlpConfFunction
import edu.washington.cs.knowitall.tool.chunk.OpenNlpChunker
import edu.washington.cs.knowitall.chunkedextractor.ReVerb
import edu.washington.cs.knowitall.chunkedextractor.{ ExtractionPart => ChunkedPart }
import edu.washington.cs.knowitall.tool.tokenize.Token
import edu.washington.cs.knowitall.tool.chunk.ChunkedToken

object Application extends Controller {
  val ollie = new Ollie()
  val ollieConf = OllieIndependentConfFunction.loadDefaultClassifier()
  val reverb = new ReVerb()
  val chunker = new OpenNlpChunker()
  val parser = new MaltParser()

  def index = Action {
    Ok(views.html.index(InputForms.textForm, InputForms.urlForm, 'text))
  }
  
  def logs = Action {
    Ok(views.html.logs(LogEntry.all()))
  }
  
  def logentry(id: Long) = Action { implicit request =>
    Ok(process(LogInput(id), Some(id)))
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

  def process(input: Input, id: Option[Long] = None)(implicit request: play.api.mvc.Request[_]) = {
    val sentenceTexts = input.sentences

    val linkId =
      id match {
        case Some(id) => Some(id)
        case None => LogEntry.fromRequest(request, sentenceTexts).save()
      }

    views.html.document(buildDocument(sentenceTexts), linkId)
  }

  def buildDocument(sentenceTexts: Seq[String]) = {
    val graphs = sentenceTexts map (_.trim) filter (!_.isEmpty) flatMap { sentence =>
      Exception.catching(classOf[Exception]) opt parser.dependencyGraph(sentence) map { (sentence, _) }
    }
    
    def olliePart(extrPart: OlliePart) = models.Part(extrPart.text, extrPart.nodes.map(_.indices))
    def reverbPart(extrPart: ChunkedPart[ChunkedToken]) = models.Part(extrPart.text, Some(extrPart.interval))
    
    val sentences = graphs map { case (text, graph) =>
      val ollieExtrs = ollie.extract(graph).map{ extr => (extr, ollieConf(extr)) }.toSeq.sortBy(-_._2).map(_._1).map { extr =>
        Extraction("Ollie", olliePart(extr.extr.arg1), olliePart(extr.extr.rel), olliePart(extr.extr.arg2), ollieConf(extr))
      }
      
      val chunked = chunker.chunk(text)
      
      val reverbExtrs = reverb.extractWithConfidence(chunked).toSeq.sortBy(_._1).map { case (conf, extr) =>
        Extraction("ReVerb", reverbPart(extr.extr.arg1), reverbPart(extr.extr.rel), reverbPart(extr.extr.arg2), conf)
      }
      
      val extrs = reverbExtrs ++ ollieExtrs

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
