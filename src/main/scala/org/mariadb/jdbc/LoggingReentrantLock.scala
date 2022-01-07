package org.mariadb.jdbc

import io.circe.{Encoder, Json, Printer}
import io.circe.syntax._
import java.sql.Connection
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import org.mariadb.jdbc.internal.logging.{Logger, LoggerFactory}

class LoggingReentrantLock extends ReentrantLock {
  import LoggingReentrantLock._

  private var connection: Connection = _

  final def setConnection(conn: Connection): Unit = {
    connection = conn
  }

  private val logger: Logger = {
    LoggerFactory.init(true)
    LoggerFactory.getLogger(getClass)
  }

  private val jsonPrinter: Printer = Printer.spaces2.copy(colonLeft = "")

  private def threadInfo(t: Thread, dropStack: Int): Json =
    Json.obj(
      "hashCode" := t.hashCode,
      "id" := t.getId,
      "name" := t.getName,
      "state" := t.getState.toString,
      "stackTrace" := t.getStackTrace.drop(dropStack).map(_.toString),
    )

  private def logDebug(methodCalled: String): Unit =
    logger.debug(jsonPrinter.print(Json.obj(
      "time" := Instant.now,
      "connection" := Option(connection).map(c => Json.obj(
        "hashCode" := connection.hashCode,
      )),
      "lock" := Json.obj(
        "methodCalled" := methodCalled,
        "hashCode" := hashCode,
        "isLocked" := isLocked,
        "callingThread" := threadInfo(Thread.currentThread, 3),
        "holdingThread" := Option(getOwner).map(threadInfo(_, 5)),
      ),
    )))

  override def lock(): Unit = {
    logDebug("lock")
    super.lock()
  }

  override def unlock(): Unit = {
    logDebug("unlock")
    super.unlock()
  }
}

object LoggingReentrantLock {
  private val instantFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneId.systemDefault())
  implicit val instantEncoder: Encoder[Instant] = Encoder[String].contramap(instantFormatter.format)
}
