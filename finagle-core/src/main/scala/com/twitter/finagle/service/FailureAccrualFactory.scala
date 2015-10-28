package com.twitter.finagle.service

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.util.DefaultLogger
import com.twitter.finagle.util.InetSocketAddressUtil.unconnected
import com.twitter.logging.Level
import com.twitter.util.{Duration, Time, Timer, TimerTask, Try}
import java.util.logging.Logger
import java.net.SocketAddress
import scala.util.Random


object FailureAccrualFactory {
  private[finagle] def wrapper(
    statsReceiver: StatsReceiver,
    numFailures: Int,
    markDeadFor: Stream[Duration],
    label: String,
    logger: Logger,
    endpoint: SocketAddress
  )(timer: Timer): ServiceFactoryWrapper = {
    new ServiceFactoryWrapper {
      def andThen[Req, Rep](factory: ServiceFactory[Req, Rep]) =
        new FailureAccrualFactory(factory, numFailures, markDeadFor, timer, statsReceiver.scope("failure_accrual"), label, logger, endpoint)
    }
  }

  private[this] val rng = new Random

  // Use equalJittered backoff in order to wait more time in between
  // each revival attempt on successive failures; if an endpoint has failed
  // previous requests, it is likely to do so again. The recent
  // "failure history" should influence how long to mark the endpoint
  // dead for.
  private[finagle] val jitteredBackoff: Stream[Duration] = Backoff.equalJittered(5.seconds, 300.seconds)

  /**
   * Add jitter in `markDeadFor` to reduce correlation.
   * Return a () => Duration type that can be used in Param.
   */
  def perturb(
    markDeadFor: Duration,
    perturbation: Float = 0.1f,
    rand: Random = rng
  ): () => Duration =
    () => {
      val ms = markDeadFor.inMilliseconds
      (ms + ms*rand.nextFloat()*perturbation).toInt.milliseconds
    }

  val role = Stack.Role("FailureAccrual")

  /**
   * An ADT representing a [[FailureAccrualFactory]]s [[Stack.Param]], which is one of the following:
   *
   * 1. [[Param.Configured]] - configures failure accrual
   * 2. [[Param.Replaced]] - replaces the standard implementation with the given one
   * 3. [[Param.Disabled]] - completely disables this role in the underlying stack
   */
  sealed trait Param {
    def mk(): (Param, Stack.Param[Param]) = (this, Param.param)
  }

  private[finagle] object Param {
    case class Configured(numFailures: Int, markDeadFor: Stream[Duration]) extends Param {
       def this(numFailures: Int, markDeadFor: () => Duration) =
         this(numFailures, Backoff.fromFunction(markDeadFor))
     }

    case class Replaced(factory: Timer => ServiceFactoryWrapper) extends Param
    case object Disabled extends Param

    implicit val param: Stack.Param[Param] = Stack.Param(Param.Configured(5, jitteredBackoff))
  }

  // -Implementation notes-
  //
  // We have to provide these wrapper functions that produce params instead of calling constructors
  // on case classes by the following reasons:
  //
  //  1. The param inserted into Stack.Params should be casted to its base type in order to tell
  //     the compiler what implicit value to look up.
  //  2. It's not possible to construct a triply-nested Scala class in Java using the sane API.
  //     See http://stackoverflow.com/questions/30809070/accessing-scala-nested-classes-from-java

  /**
   * Configures the [[FailureAccrualFactory]].
   *
   * Note there is a Java-friendly method in the API that takes `Duration` as a value, not a function.
   *
   * @param numFailures The number of consecutive failures before marking an endpoint as dead.
   * @param markDeadFor The duration to mark an endpoint as dead.
   */
  def Param(numFailures: Int, markDeadFor: () => Duration): Param =
    Param.Configured(numFailures, Backoff.fromFunction(markDeadFor))

  /**
   * Configures the [[FailureAccrualFactory]].
   *
   * @param numFailures The number of consecutive failures before marking an endpoint as dead.
   * @param markDeadFor The duration to mark an endpoint as dead.
   */
  def Param(numFailures: Int, markDeadFor: Duration): Param =
    Param.Configured(numFailures, Backoff.const(markDeadFor))

  /**
   * Replaces the [[FailureAccrualFactory]] with the [[ServiceFactoryWrapper]]
   * returned by the given function `factory`.
   */
  private[finagle] def Replaced(factory: Timer => ServiceFactoryWrapper): Param =
    Param.Replaced(factory)

  /**
   * Replaces the [[FailureAccrualFactory]] with the given [[ServiceFactoryWrapper]] `factory`.
   */
  private[finagle] def Replaced(factory: ServiceFactoryWrapper): Param =
    Param.Replaced(_ => factory)

  /**
   * Disables the [[FailureAccrualFactory]].
   */
  private[finagle] val Disabled: Param = Param.Disabled

  /**
   * Creates a [[com.twitter.finagle.Stackable]] [[com.twitter.finagle.service.FailureAccrualFactory]].
   */
  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module6[param.Stats,
      FailureAccrualFactory.Param,
      param.Timer,
      param.Label,
      param.Logger,
      Transporter.EndpointAddr,
      ServiceFactory[Req, Rep]] {
      val role = FailureAccrualFactory.role
      val description = "Backoff from hosts that we cannot successfully make requests to"

      def make(
        _stats: param.Stats,
        _param: FailureAccrualFactory.Param,
        _timer: param.Timer,
        _label: param.Label,
        _logger: param.Logger,
        _endpoint: Transporter.EndpointAddr,
        next: ServiceFactory[Req, Rep]
      ) = _param match {
        case Param.Configured(n, d) =>
          val param.Timer(timer) = _timer
          val param.Stats(statsReceiver) = _stats
          val param.Label(label) = _label
          val param.Logger(logger) = _logger
          val Transporter.EndpointAddr(endpoint) = _endpoint
          wrapper(statsReceiver, n, d, label, logger, endpoint)(timer) andThen next

        case Param.Replaced(f) =>
          val param.Timer(timer) = _timer
          f(timer) andThen next

        case Param.Disabled => next
      }
    }


  // The FailureAccrualFactory transitions between Alive, Dead, ProbeOpen,
  // and ProbeClosed. The factory starts in the Alive state. After numFailures
  // failures, the factory transitions to Dead. When it is revived,
  // it transitions to ProbeOpen. After a request is received,
  // it transitions to ProbeClosed and cannot accept any further requests until
  // the initial request is satisfied. If the request is successful, it
  // transitions back to Alive, otherwise Dead.
  //
  // The transitions can be visualized using the state diagram:
  //
  // ,<-----------.
  // Alive        |
  // |  ,---ProbeClosed
  // ∨  ∨         ^
  // Dead         |
  //  `---> ProbeOpen

  protected[finagle] sealed trait State {
    val nextMarkDeadFor: Stream[Duration]
  }
  protected[finagle] case class Alive(failureCount: Int, val nextMarkDeadFor: Stream[Duration]) extends State
  protected[finagle] case class Dead(val nextMarkDeadFor: Stream[Duration]) extends State
  protected[finagle] case class ProbeOpen(val nextMarkDeadFor: Stream[Duration]) extends State
  protected[finagle] case class ProbeClosed(val nextMarkDeadFor: Stream[Duration]) extends State
}

/**
 * A [[com.twitter.finagle.ServiceFactory]] that accrues failures, marking
 * itself unavailable when deemed unhealthy according to its parameterization.
 *
 * TODO: treat different failures differently (eg. connect failures
 * vs. not), enable different backoff strategies.
 */
class FailureAccrualFactory[Req, Rep] private[finagle](
  underlying: ServiceFactory[Req, Rep],
  numFailures: Int,
  markDeadFor: Stream[Duration],
  timer: Timer,
  statsReceiver: StatsReceiver,
  label: String = "",
  logger: Logger = DefaultLogger,
  endpoint: SocketAddress = unconnected
) extends ServiceFactory[Req, Rep] {
  import FailureAccrualFactory._

  def this(
    underlying: ServiceFactory[Req, Rep],
    numFailures: Int,
    markDeadFor: Duration,
    timer: Timer,
    statsReceiver: StatsReceiver,
    label: String,
    logger: Logger,
    endpoint: SocketAddress
  ) = this(
    underlying,
    numFailures,
    Backoff.const(markDeadFor),
    timer,
    statsReceiver,
    label,
    logger,
    endpoint)

  // Pad the back of the stream to back off for 300 seconds when the given
  // stream runs out.
  private[this] val AliveNoFailures = Alive(0, markDeadFor ++ Backoff.const(300.seconds))

  // The head of `nextMarkDeadFor` in `state` is next duration to mark dead for.
  // The tail is the remainder of the durations.
  @volatile private[this] var state: State = AliveNoFailures

  private[this] var reviveTimerTask: Option[TimerTask] = None

  private[this] val removalCounter = statsReceiver.counter("removals")
  private[this] val revivalCounter = statsReceiver.counter("revivals")

  private[this] def didFail() = synchronized {
    state match {
      case Alive(failureCount, nextMarkDeadFor) =>
        if (failureCount + 1 >= numFailures) markDead()
        else state = Alive(failureCount + 1, nextMarkDeadFor)
      case ProbeClosed(_) => markDead()
      case _ =>
    }
  }

  protected def didSucceed() = synchronized {
    // Only count revivals when the probe succeeds.
    state match {
      case ProbeClosed(_) => revivalCounter.incr()
      case _ =>
    }
    state = AliveNoFailures
  }

  protected def markDead() = synchronized {
    removalCounter.incr()
    val timerTask = timer.schedule(state.nextMarkDeadFor.head.fromNow) { startProbing() }

    // Consume the next duration to mark dead for.
    state = Dead(state.nextMarkDeadFor.tail)

    reviveTimerTask = Some(timerTask)

    if (logger.isLoggable(Level.DEBUG))
      logger.log(Level.DEBUG, s"""FailureAccrualFactory marking connection to "$label" as dead. Remote Address: ${endpoint.toString}""")
  }

  /**
   * Enter 'Probing' state.
   * The service must satisfy one request before accepting more.
   */
  private[this] def startProbing() = synchronized {
    state = ProbeOpen(state.nextMarkDeadFor)
    cancelReviveTimerTasks()
  }

  protected def isSuccess(response: Try[Rep]): Boolean = response.isReturn

  def apply(conn: ClientConnection) = {
    underlying(conn) map { service =>
      new Service[Req, Rep] {
        def apply(request: Req) = {

          // If service has just been revived, accept no further requests.
          // Note: Another request may have come in before state transitions to
          // ProbeClosed, so > 1 requests may be processing while in the
          // ProbeClosed state. The result of first to complete will determine
          // whether the factory transitions to Alive (successful) or Dead
          // (unsuccessful).
          state match {
            case ProbeOpen(_) =>
              synchronized {
                state match {
                  case ProbeOpen(next) => state = ProbeClosed(next)
                  case _ =>
                }
              }
            case _ =>
          }

          service(request).respond { response =>
            if (isSuccess(response)) didSucceed()
            else didFail()
          }
        }

        override def close(deadline: Time) = service.close(deadline)
        override def status = Status.worst(service.status,
          FailureAccrualFactory.this.status)
      }
    } onFailure { _ => didFail() }
  }

  override def status = state match {
    case Alive(_, _) | ProbeOpen(_) => underlying.status
    case Dead(_) | ProbeClosed(_) => Status.Busy
  }

  protected[this] def getState: State = state

  private[this] def cancelReviveTimerTasks(): Unit = {
    reviveTimerTask.foreach(_.cancel())
    reviveTimerTask = None
  }

  def close(deadline: Time) = underlying.close(deadline).ensure {
    cancelReviveTimerTasks()
  }

  override val toString = "failure_accrual_%s".format(underlying.toString)

  @deprecated("Please call the FailureAccrualFactory constructor that supplies a StatsReceiver", "6.22.1")
  def this(
    underlying: ServiceFactory[Req, Rep],
    numFailures: Int,
    markDeadFor: Duration,
    timer: Timer,
    label: String
  ) = this(
    underlying,
    numFailures,
    Backoff.const(markDeadFor),
    timer,
    NullStatsReceiver,
    label)
}
