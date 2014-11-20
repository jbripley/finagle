package com.twitter.finagle

import com.twitter.finagle.client.StackClient
import com.twitter.finagle.netty3.Netty3Listener
import com.twitter.finagle.param.{Label, Stats}
import com.twitter.finagle.server.{StackServer, StdStackServer, Listener}
import com.twitter.finagle.stats.{ClientStatsReceiver, ServerStatsReceiver}
import com.twitter.finagle.thrift.{ClientId, Protocols, ThriftClientRequest, HandleUncaughtApplicationExceptions}
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.transport.Transport
import com.twitter.io.Buf
import com.twitter.util.NonFatal
import com.twitter.util.{Future, Time, Local}
import java.net.SocketAddress
import org.apache.thrift.protocol.TProtocolFactory
import org.apache.thrift.transport.TMemoryInputTransport
import org.jboss.netty.buffer.{ChannelBuffer => CB}


/**
 * The `ThriftMux` object is both a [[com.twitter.finagle.Client]] and a
 * [[com.twitter.finagle.Server]] for the Thrift protocol served over
 * [[com.twitter.finagle.mux]]. Rich interfaces are provided to adhere to those
 * generated from a [[http://thrift.apache.org/docs/idl/ Thrift IDL]] by
 * [[http://twitter.github.io/scrooge/ Scrooge]] or
 * [[https://github.com/mariusaeriksen/thrift-0.5.0-finagle thrift-finagle]].
 *
 * Clients can be created directly from an interface generated from
 * a Thrift IDL:
 *
 * $clientExample
 *
 * Servers are also simple to expose:
 *
 * $serverExample
 *
 * This object does not expose any configuration options. Both clients and servers
 * are instantiated with sane defaults. Clients are labeled with the "clnt/thrift"
 * prefix and servers with "srv/thrift". If you'd like more configuration, see the
 * [[com.twitter.finagle.ThriftMux.Server]] and [[com.twitter.finagle.ThriftMux.Client]]
 * classes.
 *
 * @define clientExampleObject ThriftMux
 * @define serverExampleObject ThriftMux
 */
object ThriftMux
  extends Client[ThriftClientRequest, Array[Byte]] with ThriftRichClient
  with Server[Array[Byte], Array[Byte]] with ThriftRichServer
{
  /**
   * Base [[com.twitter.finagle.Stack Stacks]] for Mux client and servers.
   */
  private[twitter] val BaseClientStack = (ThriftMuxUtil.protocolRecorder +: Mux.client.stack)
    .replace(StackClient.Role.protoTracing, ClientRpcTracing)
  private[twitter] val BaseServerStack = ThriftMuxUtil.protocolRecorder +: Mux.server.stack

  private[this] def recordRpc(buffer: Array[Byte]): Unit = try {
    val inputTransport = new TMemoryInputTransport(buffer)
    val iprot = protocolFactory.getProtocol(inputTransport)
    val msg = iprot.readMessageBegin()
    Trace.recordRpc(msg.name)
  } catch {
    case NonFatal(_) =>
  }

  private object ClientRpcTracing extends Mux.ClientProtoTracing {
    private[this] val rpcTracer = new SimpleFilter[mux.Request, mux.Response] {
      def apply(
        request: mux.Request,
        svc: Service[mux.Request, mux.Response]
      ): Future[mux.Response] = {
        // we're reasonably sure that this filter sits just after the ThriftClientRequest's
        // message array is wrapped by a ChannelBuffer
        recordRpc(Buf.ByteArray.Owned.extract(request.body))
        svc(request)
      }
    }

    override def make(next: ServiceFactory[mux.Request, mux.Response]) =
      rpcTracer andThen super.make(next)
  }

  case class Client(
    muxer: StackClient[mux.Request, mux.Response] = Mux.client.copy(
      stack = BaseClientStack),
    // TODO: consider stuffing these into Stack.Params
    clientId: Option[ClientId] = None,
    protocolFactory: TProtocolFactory = Protocols.binaryFactory()
  ) extends com.twitter.finagle.Client[ThriftClientRequest, Array[Byte]]
      with ThriftRichClient with Stack.Parameterized[Client] {
    def stack = muxer.stack
    def params = muxer.params

    protected lazy val defaultClientName = {
      val Label(label) = params[Label]
      label
    }

    override protected lazy val stats = {
      val Stats(sr) = params[Stats]
      sr
    }

    def withParams(ps: Stack.Params): Client =
      copy(muxer=muxer.withParams(ps))

    /**
     * Produce a [[com.twitter.finagle.ThriftMux.Client]] using the provided
     * client ID.
     */
    def withClientId(clientId: ClientId): Client =
      copy(clientId=Some(clientId))

    /**
     * Produce a [[com.twitter.finagle.ThriftMux.Client]] using the provided
     * protocolFactory.
     */
    def withProtocolFactory(pf: TProtocolFactory): Client =
      copy(protocolFactory=pf)

    def newClient(dest: Name, label: String): ServiceFactory[ThriftClientRequest, Array[Byte]] =
      muxer.newClient(dest, label) map { service =>
        new Service[ThriftClientRequest, Array[Byte]] {
          def apply(req: ThriftClientRequest): Future[Array[Byte]] = {
            if (req.oneway) return Future.exception(
              new Exception("ThriftMux does not support one-way messages"))

            // We do a dance here to ensure that the proper ClientId is set when
            // `service` is applied because Mux relies on
            // com.twitter.finagle.thrift.ClientIdContext to propagate ClientIds.
            val save = Local.save()
            try {
              ClientId.set(clientId)
              // TODO set the Path here.
              val muxreq = mux.Request(Path.empty, Buf.ByteArray.Owned(req.message))
              service(muxreq) map { rep =>
                Buf.ByteArray.Owned.extract(rep.body)
              }
            } finally {
              Local.restore(save)
            }
          }

          override def isAvailable = service.isAvailable
          override def close(deadline: Time) = service.close(deadline)
        }
      }
  }

  val client = Client()
    .configured(Label("thrift"))
    .configured(Stats(ClientStatsReceiver))

  protected lazy val defaultClientName = {
    val Label(label) = client.params[Label]
    label
  }

  override protected lazy val stats = {
    val Stats(sr) = client.params[Stats]
    sr
  }

  protected val protocolFactory = client.protocolFactory

  def newClient(dest: Name, label: String) = client.newClient(dest, label)

  /**
   * Produce a [[com.twitter.finagle.ThriftMux.Client]] using the provided
   * client ID.
   */
  @deprecated("Use `ThriftMux.client.withClientId`", "6.22.0")
  def withClientId(clientId: ClientId): Client =
    client.copy(clientId=Some(clientId))

  /**
   * Produce a [[com.twitter.finagle.ThriftMux.Client]] using the provided
   * protocolFactory.
   */
  @deprecated("Use `ThriftMux.client.withProtocolFactory`", "6.22.0")
  def withProtocolFactory(pf: TProtocolFactory): Client =
    client.copy(protocolFactory=pf)

  /**
   * A server for the Thrift protocol served over [[com.twitter.finagle.mux]].
   * ThriftMuxServer is backwards-compatible with Thrift clients that use the
   * framed transport and binary protocol. It switches to the backward-compatible
   * mode when the first request is not recognized as a valid Mux message but can
   * be successfully handled by the underlying Thrift service. Since a Thrift
   * message that is encoded with the binary protocol starts with a header value of
   * 0x800100xx, Mux does not confuse it with a valid Mux message (0x80 = -128 is
   * an invalid Mux message type) and the server can reliably detect the non-Mux
   * Thrift client and switch to the backwards-compatible mode.
   *
   * Note that the server is also compatible with non-Mux finagle-thrift clients.
   * It correctly responds to the protocol up-negotiation request and passes the
   * tracing information embedded in the thrift requests to Mux (which has native
   * tracing support).
   *
   * This class can't be instantiated. For a default instance of ThriftMuxServerLike,
   * see [[com.twitter.finagle.ThriftMuxServer]]
   */

  case class ServerMuxer(
    stack: Stack[ServiceFactory[mux.Request, mux.Response]] = BaseServerStack,
    params: Stack.Params = Mux.server.params
  ) extends StdStackServer[mux.Request, mux.Response, ServerMuxer] {
    protected type In = CB
    protected type Out = CB

    protected def copy1(
      stack: Stack[ServiceFactory[mux.Request, mux.Response]] = this.stack,
      params: Stack.Params = this.params
    ) = copy(stack, params)

    protected def newListener(): Listener[CB, CB] = {
      val Label(label) = params[Label]
      val Stats(sr) = params[Stats]
      val scoped = sr.scope(label).scope("thriftmux")

      // Create a Listener that maintains gauges of how many ThriftMux and non-Mux
      // downgraded connections are listening to clients.
      new Listener[CB, CB] {
        private[this] val underlying = Netty3Listener[CB, CB](
          new thriftmux.PipelineFactory(scoped),
          params
        )

        def listen(addr: SocketAddress)(
          serveTransport: Transport[CB, CB] => Unit
        ): ListeningServer = underlying.listen(addr)(serveTransport)
      }
    }

    protected def newDispatcher(
      transport: Transport[In, Out],
      service: Service[mux.Request, mux.Response]
    ) = {
      val param.Tracer(tracer) = params[param.Tracer]
      new mux.ServerDispatcher(transport, service, true, mux.lease.exp.ClockedDrainer.flagged,
        tracer)
    }
  }

  val serverMuxer = ServerMuxer()

  case class Server(
    muxer: StackServer[mux.Request, mux.Response] = serverMuxer,
    protocolFactory: TProtocolFactory = Protocols.binaryFactory()
  ) extends com.twitter.finagle.Server[Array[Byte], Array[Byte]]
      with ThriftRichServer with Stack.Parameterized[Server] {
    def stack = muxer.stack
    def params = muxer.params

    /**
     * Produce a [[com.twitter.finagle.ThriftMuxServerLike]] using the provided
     * protocolFactory.
     */
    def withProtocolFactory(pf: TProtocolFactory): Server =
      copy(protocolFactory=pf)

    def withParams(ps: Stack.Params): Server =
      copy(muxer=muxer.withParams(ps))

    private[this] val muxToArrayFilter =
      new Filter[mux.Request, mux.Response, Array[Byte], Array[Byte]] {
        def apply(
          request: mux.Request, service: Service[Array[Byte], Array[Byte]]
        ): Future[mux.Response] = {
          val reqBytes = Buf.ByteArray.Owned.extract(request.body)
          service(reqBytes) map { repBytes =>
            mux.Response(Buf.ByteArray.Owned(repBytes))
          }
        }
      }

    private[this] val tracingFilter = new SimpleFilter[Array[Byte], Array[Byte]] {
      def apply(request: Array[Byte], svc: Service[Array[Byte], Array[Byte]]): Future[Array[Byte]] = {
        recordRpc(request)
        svc(request)
      }
    }

    def serve(addr: SocketAddress, factory: ServiceFactory[Array[Byte], Array[Byte]]) = {
      muxer.serve(addr, factory map { service =>
        // Need a HandleUncaughtApplicationExceptions filter here to maintain
        // the backward compatibility with non-mux thrift clients. Mux thrift
        // clients get the same semantics as a side effect.
        val uncaughtExceptionsFilter = new HandleUncaughtApplicationExceptions(protocolFactory)
        muxToArrayFilter andThen tracingFilter andThen uncaughtExceptionsFilter andThen service
      })
    }

  }

  val server: Server = Server()
    .configured(Label("thrift"))
    .configured(Stats(ServerStatsReceiver))

  def serve(addr: SocketAddress, factory: ServiceFactory[Array[Byte], Array[Byte]]) =
    server.serve(addr, factory)
}
