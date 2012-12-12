package controllers

import edu.washington.cs.knowitall.ollie.Ollie
import models.{FileInput, Input, TextInput, UrlInput}
import play.api._
import play.api.data.{Form, Forms}
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

object Application extends Controller {
  val ollie = new Ollie()
  val ollieConf = OllieIndependentConfFunction.loadDefaultClassifier()
  val parser = new MaltParser()

  def index = Action {
    Ok(views.html.index(InputForms.textForm, InputForms.urlForm, 'text))
  }

  def submitText = Action { implicit request =>
    InputForms.textForm.bindFromRequest.fold(
      errors => BadRequest(views.html.index(errors, InputForms.urlForm, 'text)),
      input => Ok(process(input)))
  }

  def submitFile = Action(parse.multipartFormData) { request =>
    request.body.file("file").map { file =>
      Ok(process(FileInput.process(file.ref.file)))
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
      "error" -> "Missing file"
    )
  }
}

  def submitUrl = Action { implicit request =>
    InputForms.urlForm.bindFromRequest.fold(
      errors => BadRequest(views.html.index(InputForms.textForm, errors, 'url)),
      input => Ok(process(input)))
  }

  def process(input: Input) = {
    val sentences = input.sentences
    val graphs = sentences map (_.trim) filter (!_.isEmpty) flatMap { sentence =>
      Exception.catching(classOf[Exception]) opt parser.dependencyGraph(sentence)
    }
    val ollieExtrs = graphs flatMap ollie.extract

    val extrs = ollieExtrs.map { extr =>
      Extraction(extr.extr.arg1.text, extr.extr.rel.text, extr.extr.arg2.text, ollieConf(extr))
    }.sortBy(-_.conf)

    views.html.results(extrs)
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