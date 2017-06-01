package com.twitter.finagle.memcached.protocol.text.server

import com.twitter.finagle.memcached.protocol._
import com.twitter.finagle.memcached.protocol.text.Encoder._
import com.twitter.io.{Buf, ByteWriter}
import java.nio.charset.StandardCharsets

/**
 * Used by the server.
 */
private[finagle] object ResponseToBuf {

  private[this] val ZERO = Buf.Utf8("0")
  private[this] val VALUE = Buf.Utf8("VALUE")

  private[this] val STORED = Buf.Utf8("STORED")
  private[this] val NOT_STORED = Buf.Utf8("NOT_STORED")
  private[this] val EXISTS = Buf.Utf8("EXISTS")
  private[this] val NOT_FOUND = Buf.Utf8("NOT_FOUND")
  private[this] val DELETED = Buf.Utf8("DELETED")

  private[this] def encodeResponse(response: Seq[Buf]): Buf = {
    // + 2 to estimated size for DELIMITER.
    val bw = ByteWriter.dynamic(10 * response.size + 2)
    response.foreach { token =>
      bw.writeBytes(token)
      bw.writeBytes(SPACE)
    }
    bw.writeBytes(DELIMITER)

    bw.owned()
  }

  private[this] def writeResponseWithData(
    response: Seq[Buf],
    data: Buf,
    casUnique: Option[Buf],
    bw: ByteWriter
  ): ByteWriter = {
    response.foreach { token =>
      bw.writeBytes(token)
      bw.writeBytes(SPACE)
    }

    bw.writeBytes(data.length.toString.getBytes(StandardCharsets.US_ASCII))

    casUnique.foreach { token =>
      bw.writeBytes(SPACE)
      bw.writeBytes(token)
    }

    bw.writeBytes(DELIMITER)
    bw.writeBytes(data)
    bw.writeBytes(DELIMITER)
    bw
  }

  private[this] def encodeResponseLines(lines: Seq[Seq[Buf]]): Buf = {
    // + 5 to estimated size for END + DELIMITER.
    val bw = ByteWriter.dynamic(100 * lines.size + 5)

    lines.foreach { tokens =>
      tokens.foreach { token =>
        bw.writeBytes(token)
        bw.writeBytes(SPACE)
      }
      bw.writeBytes(DELIMITER)
    }
    bw.writeBytes(END)
    bw.writeBytes(DELIMITER)
    bw.owned()
  }

  def encode(message: Response): Buf = message match {
    case Stored => encodeResponse(Seq(STORED))
    case NotStored => encodeResponse(Seq(NOT_STORED))
    case Exists => encodeResponse(Seq(EXISTS))
    case Deleted => encodeResponse(Seq(DELETED))
    case NotFound => encodeResponse(Seq(NOT_FOUND))
    case NoOp => encodeResponse(Nil)
    case Number(value) => encodeResponse(Seq(Buf.Utf8(value.toString)))
    case Error(cause) =>
      val formatted: Seq[Array[Byte]] = ExceptionHandler.format(cause)
      encodeResponse(formatted.map { Buf.ByteArray.Owned(_) })
    case InfoLines(lines) =>
      val statLines = lines map { line =>
        val key = line.key
        val values = line.values
        Seq(key) ++ values
      }
      encodeResponseLines(statLines)
    case Values(values) =>
      // + 5 to estimated size for END + DELIMITER.
      val bw = ByteWriter.dynamic(100 * values.size + 5)

      val tokensWithData = values map {
        case Value(key, value, casUnique, Some(flags)) =>
          writeResponseWithData(Seq(VALUE, key, flags), value, casUnique, bw)
        case Value(key, value, casUnique, None) =>
          writeResponseWithData(Seq(VALUE, key, ZERO), value, casUnique, bw)
      }
      bw.writeBytes(END)
      bw.writeBytes(DELIMITER)
      bw.owned()
  }
}

object ExceptionHandler {
  private val ERROR         = "ERROR".getBytes
  private val CLIENT_ERROR  = "CLIENT_ERROR".getBytes
  private val SERVER_ERROR  = "SERVER_ERROR".getBytes
  private val Newlines      = "[\\r\\n]".r

  def format(e: Throwable): Seq[Array[Byte]] = e match {
    case e: NonexistentCommand =>
      Seq(ERROR)
    case e: ClientError        =>
      Seq(CLIENT_ERROR, Newlines.replaceAllIn(e.getMessage, " ").getBytes)
    case e: ServerError        =>
      Seq(SERVER_ERROR, Newlines.replaceAllIn(e.getMessage, " ").getBytes)
    case t                     =>
      throw t
  }
}
