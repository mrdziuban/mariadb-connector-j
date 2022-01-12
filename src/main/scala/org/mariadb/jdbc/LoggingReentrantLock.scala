package org.mariadb.jdbc

import io.circe.{Encoder, Json, Printer}
import io.circe.syntax._
import java.sql.Connection
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import org.mariadb.jdbc.internal.logging.{Logger, LoggerFactory}
import sun.misc.Unsafe

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

  private def threadInfo(t: Thread, dropStack: Int): Json =
    Json.obj(
      "address" := getMemoryAddress(t),
      "hashCode" := t.hashCode,
      "id" := t.getId,
      "name" := t.getName,
      "state" := t.getState.toString,
      "stackTrace" := t.getStackTrace.drop(dropStack).map(_.toString),
    )

  private def logDebug(methodCalled: String): Unit =
    logger.debug(Json.obj(
      "time" := Instant.now,
      "connection" := Option(connection).map(c => Json.obj(
        "address" := getMemoryAddress(connection),
        "hashCode" := connection.hashCode,
      )),
      "lock" := Json.obj(
        "methodCalled" := methodCalled,
        "address" := getMemoryAddress(this),
        "hashCode" := hashCode,
        "isLocked" := isLocked,
        "callingThread" := threadInfo(Thread.currentThread, 3),
        "holdingThread" := Option(getOwner).map(threadInfo(_, 5)),
      ),
    ).noSpaces)

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

  private lazy val unsafe: Unsafe = {
    val theUnsafe = classOf[Unsafe].getDeclaredField("theUnsafe")
    theUnsafe.setAccessible(true)
    theUnsafe.get(null).asInstanceOf[Unsafe]
  }

  def getMemoryAddress(x: AnyRef): String = {
    val objects = Array(x)
    val offset = unsafe.arrayBaseOffset(objects.getClass)
    val scale = unsafe.arrayIndexScale(objects.getClass)
    java.lang.Long.toHexString((unsafe.getInt(objects, offset) & 0xFFFFFFFFL) * 8)
  }
}
