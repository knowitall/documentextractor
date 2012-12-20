package models

import org.joda.time.DateTime
import play.api.db.DB
import play.api.Play.current
import anorm._
import play.api.mvc.RequestHeader
import scala.util.control.Exception.catching
import java.net.UnknownHostException
import java.net.InetAddress
import java.sql.Timestamp

class LogEntry(ip: String, host: Option[String], timestamp: DateTime, sentences: Seq[String]) {
  /*
  def from(ip: String, date: DateTime, sentences: Seq[String]) = {
    LogEntry(ip, date, sentences)
  }
  */

  def save() = {
    DB.withConnection { implicit conn =>
      val s = SQL("insert into LogEntry (ip, host, timestamp, sentences) values ({ip}, {host}, {timestamp}, {sentences})")
        .on(
            'ip -> "foo",
            'host -> "bar",
            'timestamp -> new Timestamp(timestamp.getMillis()),
            'sentences -> sentences.mkString("\n")
           )

      s.executeInsert()
    }
  }
}

object LogEntry {
  def get(id: Int) = {

  }

  def fromRequest(/*request: RequestHeader, */sentences: Seq[String]) = {
    //val remoteIp = request.remoteAddress
    //val remoteHost = catching(classOf[UnknownHostException]) opt (InetAddress.getByName(remoteIp).getHostName)

    new LogEntry("foo", Some("bar"), DateTime.now, sentences)
  }
}