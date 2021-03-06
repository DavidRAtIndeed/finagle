package com.twitter.finagle.netty4.ssl.server

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.ssl.Netty4SslTestComponents
import com.twitter.finagle.ssl.OpportunisticTls
import com.twitter.finagle.transport.Transport
import io.netty.buffer.{ByteBuf, ByteBufAllocator}
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.channel.embedded.EmbeddedChannel
import org.scalacheck.Gen
import org.scalatest.FunSuite
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import scala.collection.mutable

class Netty4TlsSnoopingHandlerTest extends FunSuite with ScalaCheckDrivenPropertyChecks {

  /*
  See https://tools.ietf.org/html/rfc5246#section-6.2.1 for what a TLS frame looks like.
  We're specifically looking for the handshake frame type (22) and a TLS major version 3.
  We also know that the length must be in the range [1, 2^14]. This means that recognized
  prefixes will be of the form [22, 3, ???, ??? <= 0x3f, ???]
   */

  private val toShortHandshake = Array[Byte](22, 3, 0, 0, 0)

  private val genValidTlsPrefix: Gen[Array[Byte]] = {
    for {
      a <- Gen.choose(Byte.MinValue, Byte.MaxValue)
      b <- Gen.choose(Byte.MinValue, Byte.MaxValue)
      c <- Gen.choose(Byte.MinValue, Byte.MaxValue)
    } yield {
      // [22, 3, ???, ??? <= 0x3f, ???]
      Array[Byte](22, 3, a, (0x3f & b).toByte, c)
    }
  }

  private val genInvalidTlsPrefix: Gen[Array[Byte]] = {
    for {
      tpe <- Gen.choose(Byte.MinValue, Byte.MaxValue) if tpe != 22
      v <- Gen.choose(Byte.MinValue, Byte.MaxValue) if tpe != 3
      toLarge <- Gen.oneOf(true, false)
    } yield {
      // [22, 3, ???, ??? <= 0x3f, ???]
      val lenByte = if (toLarge) 0x3f + 1 else 1
      Array[Byte](tpe, v, 0, lenByte.toByte, 0)
    }
  }

  private class UserEventInterceptor extends ChannelInboundHandlerAdapter {

    val events: mutable.Queue[Any] = mutable.Queue.empty[Any]

    override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
      events.enqueue(evt)
    }
  }

  private[this] val params: Stack.Params = {
    Stack.Params.empty + Transport.ServerSsl(Some(Netty4SslTestComponents.serverConfig)) +
      OpportunisticTls.Param(OpportunisticTls.Desired)
  }

  private[this] def arrayToBuf(bytes: Array[Byte]): ByteBuf = {
    val bb = ByteBufAllocator.DEFAULT.heapBuffer(bytes.length)
    bb.writeBytes(bytes)
    bb
  }

  private[this] def getEvent(ch: EmbeddedChannel): Any = {
    ch.pipeline.get(classOf[UserEventInterceptor]).events.dequeue()
  }

  private[this] def channel(): EmbeddedChannel = {
    new EmbeddedChannel(new Netty4TlsSnoopingHandler(params), new UserEventInterceptor)
  }

  test("recognizes known SSL/TLS handshake prefixes") {
    forAll(genValidTlsPrefix) { bytes =>
      val ch = channel()
      ch.writeInbound(arrayToBuf(bytes))
      assert(getEvent(ch) == Netty4TlsSnoopingHandler.Result.Secure)
    }
  }

  test("rejects unknown SSL/TLS handshake prefixes") {
    forAll(genInvalidTlsPrefix) { bytes =>
      val ch = channel()
      ch.writeInbound(arrayToBuf(bytes))
      assert(getEvent(ch) == Netty4TlsSnoopingHandler.Result.Cleartext)
    }
  }

  test("Handshake frame of 0 length is rejected") {
    val ch = channel()
    ch.writeInbound(arrayToBuf(toShortHandshake))
    assert(getEvent(ch) == Netty4TlsSnoopingHandler.Result.Cleartext)
  }
}
