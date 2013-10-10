package models

import org.joda.time.DateTime
import play.api.Play.current
import anorm._
import play.api.mvc.RequestHeader
import play.api.db.DB
import scala.util.control.Exception.catching
import java.net.UnknownHostException
import java.net.InetAddress
import java.sql.Timestamp
import anorm.SqlParser.ResultSet
import anorm.SqlResult

class LogEntry(val ip: String, val host: Option[String], val timestamp: DateTime, val sentences: Seq[String]) {
  def sentenceSummary = {
    val sentence = sentences(0)
    if (sentence.length > 60) {
      sentence.take(60) + "..."
    }
    else {
      sentence
    }
  }

  def persist() = {
    DB.withConnection { implicit conn =>
      val s = SQL("insert into LogEntry (ip, host, timestamp, sentences) values ({ip}, {host}, {timestamp}, {sentences})")
        .on(
            'ip -> ip,
            'host -> host,
            'timestamp -> new Timestamp(timestamp.getMillis()),
            'sentences -> sentences.mkString("\n")
           )

      import java.math.BigDecimal
      s.executeInsert[Option[BigDecimal]](
          ResultSetParser.singleOpt[BigDecimal](
              anorm.SqlParser.get[BigDecimal]("1"))) map (_.longValue)
    }
  }
}

case class PersistedLogEntry(id: Long, override val ip: String, override val host: Option[String], override val timestamp: DateTime, override val sentences: Seq[String])
extends LogEntry(ip, host, timestamp, sentences)

object LogEntry {
  import anorm.SqlParser._
  private def entryParser =
    (long("id") ~ str("ip") ~ str("host") ~ get[java.util.Date]("timestamp") ~ str("sentences") map (flatten) *)


  def find(id: Long = 0) = {
    DB.withConnection { implicit conn =>
      conn.setAutoCommit(false)
      SQL(
        """select id, ip, host, timestamp, sentences from LogEntry where id = {id}""")
        .on('id -> id)
        .as(entryParser)
    }.headOption.map {
      case (id, ip, host, timestamp, sentences) =>
        new PersistedLogEntry(id, ip, Some(host), new DateTime(timestamp), sentences.split("\n"))
    }
  }

  def all() = {
    val entries =
      DB.withConnection { implicit conn =>
        conn.setAutoCommit(false)
        SQL(
          """select id, ip, host, timestamp, sentences from LogEntry""")
          .as(entryParser)
      }

    entries.map {
      case (id, ip, host, timestamp, sentences) =>
        new PersistedLogEntry(id, ip, Some(host), new DateTime(timestamp), sentences.split("\n"))
    }.toSeq
  }

  def fromRequest(request: play.api.mvc.Request[_], sentences: Seq[String]) = {
    val remoteIp = request.remoteAddress
    val remoteHost = catching(classOf[UnknownHostException]) opt (InetAddress.getByName(remoteIp).getHostName)

    new LogEntry(remoteIp, remoteHost, DateTime.now, sentences)
  }
}
