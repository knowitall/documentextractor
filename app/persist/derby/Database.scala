package persist.derby

import java.sql.DriverManager
import edu.washington.cs.knowitall.common.Resource.using

import anorm._

object Database extends App {
  def idResultSetParser(implicit extractor: anorm.Column[java.math.BigDecimal]) =
    ResultSetParser.singleOpt[java.math.BigDecimal](
      anorm.SqlParser.get[java.math.BigDecimal]("1"))
      
  def initialize() {
    val driverClass = "org.apache.derby.jdbc.EmbeddedDriver"
    val driver = Class.forName(driverClass).newInstance()

    val protocol = "jdbc:derby:docex"
    using(DriverManager.getConnection(protocol + ";create=true")) { conn =>
      try {
        SQL("""DROP TABLE LogEntry""").executeUpdate()(conn)
      }
      catch { case e => }
      SQL("""CREATE TABLE LogEntry (
            id bigint NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
            ip varchar(255) NOT NULL,
            host varchar(255) NOT NULL,
            timestamp timestamp NOT NULL,
            sentences clob NOT NULL,
            CONSTRAINT primary_key PRIMARY KEY (id)
            )""").executeUpdate()(conn)
    }
  }

  initialize()
}