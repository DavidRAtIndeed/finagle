package com.twitter.finagle.thrift

import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicReference
import java.lang.reflect.{Method, ParameterizedType, Proxy}

import scala.reflect.BeanProperty

import org.apache.thrift.{TBase, TApplicationException}
import org.apache.thrift.protocol.{TBinaryProtocol, TMessage, TMessageType, TProtocol}

import org.jboss.netty.buffer.{ChannelBuffers, ChannelBuffer}
import org.jboss.netty.channel._

import com.twitter.finagle.channel.TooManyDicksOnTheDanceFloorException

/**
 * The ThriftCall object represents a thrift dispatch on the
 * channel. The method name & argument thrift structure (POJO) is
 * given.
 */
class ThriftCall[A <: TBase[_, _], R <: TBase[_, _]](
  @BeanProperty val method: String,
  args: A,
  replyClass: Class[R])
{
  private[thrift] def readRequestArgs(p: TProtocol) {
    args.read(p)
    p.readMessageEnd()
  }

  private[thrift] def writeRequest(seqid: Int, p: TProtocol) {
    p.writeMessageBegin(new TMessage(method, TMessageType.CALL, seqid))
    args.write(p)
    p.writeMessageEnd()
  }

  private[thrift] def writeReply(seqid: Int, p: TProtocol, reply: TBase[_, _]) {
    // Write server replies
    p.writeMessageBegin(new TMessage(method, TMessageType.REPLY, seqid))
    reply.write(p)
    p.writeMessageEnd()
  }

  private[thrift] def readResponse(p: TProtocol) = {
    // Read client responses
    val result = replyClass.newInstance()
    result.read(p)
    p.readMessageEnd()
    result
  }

  /**
   * Produce a new reply instance.
   */
  def newReply() = replyClass.newInstance()

  /**
   * Wrap a ReplyClass in a ThriftReply.
   */
  def reply(reply: R) =
    new ThriftReply[R](reply, this)

  /**
   * Read the argument list
   */
  def arguments: A = args.asInstanceOf[A]
}

/**
 * Encapsulates the result of a call to a Thrift service.
 */
case class ThriftReply[R <: TBase[_, _]](
  response: R,
  call: ThriftCall[_ <: TBase[_, _], _ <: TBase[_, _]])

class ThriftCallFactory[A <: TBase[_, _], R <: TBase[_, _]](
  val method: String,
  argClass: Class[A],
  replyClass: Class[R])
{
  private[this] def newArgInstance() = argClass.newInstance
  def newInstance() = new ThriftCall(method, newArgInstance(), replyClass)
}

/**
 * A registry for Thrift types. Register ThriftCallFactory instances encapsulating
 * the types to be decoded by the ThriftServerCodec with this singleton.
 */
object ThriftTypes
  extends scala.collection.mutable.HashMap[String, ThriftCallFactory[_, _]]
{
  def add(c: ThriftCallFactory[_, _]): Unit = put(c.method, c)

  override def apply(method: String) = {
    try {
      super.apply(method)
    } catch {
      case e: NoSuchElementException =>
        throw new TApplicationException(
          TApplicationException.UNKNOWN_METHOD,
          "unknown method '%s'".format(method))
    }
  }
}

abstract class ThriftCodec extends SimpleChannelHandler {
  protected val protocolFactory = new TBinaryProtocol.Factory(true, true)
  protected val currentCall = new AtomicReference[ThriftCall[_, _]]
  protected var seqid = 0
}


class UnrecognizedResponseException extends Exception

class ThriftServerCodec extends ThriftCodec {
  /**
   * Writes replies to clients.
   */
  override def handleDownstream(ctx: ChannelHandlerContext, c: ChannelEvent) {
    if (!c.isInstanceOf[MessageEvent]) {
      super.handleDownstream(ctx, c)
      return
    }

    val m = c.asInstanceOf[MessageEvent]
    m getMessage match {
      case reply@ThriftReply(response, call) =>
        // Writing replies as a server
        val buf = ChannelBuffers.dynamicBuffer()
        val transport = new ChannelBufferTransport(buf)
        val oprot = protocolFactory.getProtocol(transport)
        call.writeReply(seqid, oprot, response)
        Channels.write(ctx, c.getFuture, buf, m.getRemoteAddress)
      case _ =>
        Channels.fireExceptionCaught(ctx, new UnrecognizedResponseException)
    }
  }

  /**
   * Receives requests from clients.
   */
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    e.getMessage match {
      case buffer: ChannelBuffer =>
        val transport = new ChannelBufferTransport(buffer)
        val iprot = protocolFactory.getProtocol(transport)

        try {
          readThriftMessage(ctx, e, iprot)
        } catch {
          case exc: Throwable =>
            Channels.fireExceptionCaught(ctx, exc)
            Channels.close(ctx, Channels.future(ctx.getChannel))
        }
    }
  }

  def readThriftMessage(ctx: ChannelHandlerContext, e: MessageEvent, iprot: TProtocol) {
    val msg = iprot.readMessageBegin()

    if (msg.`type` == TMessageType.EXCEPTION) {
      val exc = TApplicationException.read(iprot)
      iprot.readMessageEnd()
      currentCall.set(null)
      throw(exc)
    }

    // Adopt the sequence ID from the client.
    seqid = msg.seqid

    val request = ThriftTypes(msg.name).newInstance()
    request.readRequestArgs(iprot)
    Channels.fireMessageReceived(ctx, request, e.getRemoteAddress)
  }
}

class ThriftUnframedServerCodec extends ThriftServerCodec {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    e.getMessage match {
      case buffer: ChannelBuffer =>
        /* Attempt to read as-if the full frame has been received.  NotEnoughBytesException
         * is raise if there aren't enough bytes.  This is wasteful if the client
         * doesn't send the full frame.  Most implementations do and most requests are
         * small.
         */
        buffer.markReaderIndex()

        val transport = new SafeChannelBufferTransport(buffer)
        val iprot = protocolFactory.getProtocol(transport)

        try {
          readThriftMessage(ctx, e, iprot)
        } catch {
          case exc: NotEnoughBytesException =>
            // Not enough bytes.  Try ga
            buffer.resetReaderIndex()
          case exc: Throwable =>
            Channels.fireExceptionCaught(ctx, exc)
            Channels.close(ctx, Channels.future(ctx.getChannel))
        }
    }
  }
}


class ThriftClientCodec extends ThriftCodec {
  /**
   * Sends requests to servers.
   */
  override def handleDownstream(ctx: ChannelHandlerContext, c: ChannelEvent) {
    if (!c.isInstanceOf[MessageEvent]) {
      super.handleDownstream(ctx, c)
      return
    }

    val m = c.asInstanceOf[MessageEvent]
    m getMessage match {
      case call: ThriftCall[_, _] =>
        if (!currentCall.compareAndSet(null, call)) {
          val exc = new TooManyDicksOnTheDanceFloorException
          Channels.fireExceptionCaught(ctx, exc)
          c.getFuture.setFailure(exc)
          return
        }

        val buf = ChannelBuffers.dynamicBuffer()
        val transport = new ChannelBufferTransport(buf)
        val p = protocolFactory.getProtocol(transport)
        seqid += 1
        call.writeRequest(seqid, p)
        Channels.write(ctx, c.getFuture, buf, m.getRemoteAddress)
      case _ =>
        Channels.fireExceptionCaught(ctx, new UnrecognizedResponseException)
    }
  }

  /**
   * Receives replies from servers.
   */
  override def handleUpstream(ctx: ChannelHandlerContext, c: ChannelEvent) {
    if (!c.isInstanceOf[MessageEvent]) {
      super.handleUpstream(ctx, c)
      return
    }

    val e = c.asInstanceOf[MessageEvent]
    e.getMessage match {
      case buffer: ChannelBuffer =>
        val transport = new ChannelBufferTransport(buffer)
        val iprot = protocolFactory.getProtocol(transport)
        val msg = iprot.readMessageBegin()

        if (msg.`type` == TMessageType.EXCEPTION) {
          val exc = TApplicationException.read(iprot)
          iprot.readMessageEnd()
          Channels.fireExceptionCaught(ctx, exc)
          currentCall.set(null)
          return
        }

        if (msg.seqid != seqid) {
          // This means the channel is in an inconsistent state, so we
          // both fire the exception (upstream), and close the channel
          // (downstream).
          val exc = new TApplicationException(
            TApplicationException.BAD_SEQUENCE_ID,
            "out of sequence response (got %d expected %d)".format(msg.seqid, seqid))
          Channels.fireExceptionCaught(ctx, exc)
          Channels.close(ctx, Channels.future(ctx.getChannel))
          return
        }

        // Receiving replies as a client
        val result = currentCall.get().readResponse(iprot)

        // Done with the current call cycle: we can now accept another
        // request.
        currentCall.set(null)

        Channels.fireMessageReceived(ctx, result, e.getRemoteAddress)

      case _ =>
        Channels.fireExceptionCaught(ctx, new UnrecognizedResponseException)
    }
  }
}

