package models

import play.api.db.DB
import anorm._
import play.api.Play.current

class Annotation(val logentryId: Long, val annotation: Boolean, val source: String, val sentence: String, val arg1: String, val rel: String, val arg2: String) {
  def persist() = {
    DB.withConnection { implicit conn =>
      val s = SQL("insert into Annotation (logentry_id, annotation, source, sentence, arg1, rel, arg2) values ({logentryId}, {annotation}, {source}, {sentence}, {arg1}, {rel}, {arg2})")
        .on(
          'logentryId -> logentryId,
          'annotation -> annotation,
          'source -> source,
          'sentence -> sentence,
          'arg1 -> arg1,
          'rel -> rel,
          'arg2 -> arg2)

      import java.math.BigDecimal
      s.executeInsert[Option[BigDecimal]](
        ResultSetParser.singleOpt[BigDecimal](
          anorm.SqlParser.get[BigDecimal]("1"))) map (_.longValue)
    }
  }
  
  def positive = annotation
  def negative = !annotation
  
  def contains(sentence: Sentence, extraction: Extraction) = {
    this.sentence == sentence.text &&
      extraction.arg1.string == this.arg1 &&
      extraction.rel.string == this.rel &&
      extraction.arg2.string == this.arg2
  }
}

object Annotation {
  import anorm.SqlParser._
  private def annotationParser = 
    (long("id") ~ long("logentry_id") ~ bool("annotation") ~ str("source") ~ str("sentence") ~ str("arg1") ~ str("rel") ~ str("arg2") map (flatten) *)

  def findAll(logentryId: Long, source: String) = {
    DB.withConnection { implicit conn =>
      conn.setAutoCommit(false)
      SQL(
        """select id, logentry_id, annotation, source, sentence, arg1, rel, arg2 from Annotation where logentry_id = {logentryId} and source = {source}""")
        .on('logentryId -> logentryId, 'source -> source)
        .as(annotationParser)
    }.map {
      case (id, logentryId, judgement, source, sentence, arg1, rel, arg2) =>
        new PersistedAnnotation(id, logentryId, judgement, source, sentence, arg1, rel, arg2)
    }
  }
  
  def delete(logentryId: Long, annotation: Boolean, source: String, sentence: String, arg1: String, rel: String, arg2: String) {
    DB.withConnection { implicit conn =>
      SQL("""delete from annotation where logentry_id = {logentryId} and annotation = {annotation} and source = {source} and sentence = {sentence} and arg1 = {arg1} and rel = {rel} and arg2 = {arg2}""")
      .on(
          'logentryId -> logentryId,
          'annotation -> annotation,
          'source -> source,
          'sentence -> sentence,
          'arg1 -> arg1,
          'rel -> rel,
          'arg2 -> arg2
          ).execute()
    }
  }
}

case class PersistedAnnotation(id: Long, override val logentryId: Long, override val annotation: Boolean, override val source: String, override val sentence: String, override val arg1: String, override val rel: String, override val arg2: String)
  extends Annotation(logentryId, annotation, source, sentence, arg1, rel, arg2)