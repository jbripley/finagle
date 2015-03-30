package com.twitter.finagle.stats

import com.twitter.common.metrics.{Histogram, Metrics}
import com.twitter.ostrich.stats.StatsSummary
import com.twitter.util.events.Sink
import java.util
import org.openjdk.jmh.annotations._
import scala.util.Random

// ./sbt 'project finagle-benchmark' 'run .*StatsReceiverBenchmark.*'
@Threads(3)
class StatsReceiverBenchmark {
  import StatsReceiverBenchmark._

  private[this] def newStat(statRecv: StatsReceiver): Stat =
    statRecv.stat("stats_receiver_histogram")

  private[this] def add(addState: AddState, stat: Stat): Unit = {
    val i = addState.i
    addState.i += 1
    stat.add(i)
  }

  @Benchmark
  def newStatOstrich(state: StatsReceiverState): Stat =
    newStat(state.ostrichStatsReceiver)

  @Benchmark
  def addOstrich(addState: AddState, state: StatState): Unit =
    add(addState, state.ostrichStat)

  @Benchmark
  def queryOstrich(state: QueryState): StatsSummary =
    state.ostrichGet()

  @Benchmark
  def newStatMetricsCommons(state: StatsReceiverState): Stat =
    newStat(state.metricsStatsReceiver)

  @Benchmark
  def addMetricsCommons(addState: AddState, state: StatState): Unit =
    add(addState, state.metricsStat)

  @Benchmark
  def queryMetricsCommons(state: QueryState): util.Map[String, Number] =
    state.metricsGet()

  @Benchmark
  def newStatMetricsBucketed(state: StatsReceiverState): Stat =
    newStat(state.metricsBucketedStatsReceiver)

  @Benchmark
  def addMetricsBucketed(addState: AddState, state: StatState): Unit =
    add(addState, state.metricsBucketedStat)

  @Benchmark
  def queryMetricsBucketed(state: QueryState): util.Map[String, Number] =
    state.metricsBucketedGet()

}

object StatsReceiverBenchmark {
  private[this] val ostrich = new OstrichStatsReceiver

  private[this] val metrics = new MetricsStatsReceiver(
    Metrics.createDetached(),
    Sink.default,
    (n: String) => new Histogram(n))

  private[this] val metricsBucketed = new MetricsStatsReceiver(
    Metrics.createDetached(),
    Sink.default,
    (n: String) => new MetricsBucketedHistogram(n))

  @State(Scope.Benchmark)
  class StatsReceiverState {
    val ostrichStatsReceiver: StatsReceiver = ostrich
    val metricsStatsReceiver: StatsReceiver = metrics
    val metricsBucketedStatsReceiver: StatsReceiver = metricsBucketed
  }

  @State(Scope.Benchmark)
  class StatState {
    val ostrichStat: Stat = ostrich.stat("histo")
    val metricsStat: Stat = metrics.stat("histo")
    val metricsBucketedStat: Stat = metricsBucketed.stat("histo")
  }

  @State(Scope.Thread)
  class AddState {
    var i = 0
  }

  @State(Scope.Benchmark)
  class QueryState {
    var statName: String = ""
    val rng = new Random(31415926535897932L)

    def ostrichGet(): StatsSummary = ostrich.repr.get()
    def metricsGet(): util.Map[String, Number] = metrics.registry.sample()
    def metricsBucketedGet(): util.Map[String, Number] = metricsBucketed.registry.sample()

    @Setup(Level.Trial)
    def setup(): Unit = {
      val oStat = ostrich.stat("my_stat")
      val mStat = metrics.stat("my_stat")
      val mbStat = metricsBucketed.stat("my_stat")
      (1 to 100000).foreach { x =>
        val rand = rng.nextInt(x)
        oStat.add(rand)
        mStat.add(rand)
        mbStat.add(rand)
      }
    }

    @TearDown(Level.Trial)
    def teardown(): Unit = {
      ostrich.repr.clearAll()
    }
  }
}
