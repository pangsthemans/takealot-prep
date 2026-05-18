package services

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.graphite.{Graphite, GraphiteReporter}
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logger}

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetricsService @Inject()(
  config: Configuration,
  lifecycle: ApplicationLifecycle
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  private val host     = config.get[String]("metrics.graphite.host")
  private val port     = config.get[Int]("metrics.graphite.port")
  private val interval = config.get[Int]("metrics.graphite.interval")
  private val prefix   = config.get[String]("metrics.graphite.prefix")

  // MetricRegistry is the central store for all metrics in this service.
  // Counter.inc() is thread-safe — safe to call from concurrent requests.
  private val registry = new MetricRegistry()

  // Graphite speaks a simple TCP plaintext protocol:
  //   "<metric.name> <value> <unix-timestamp>\n"
  // The reporter handles serialisation, connection, and reconnection automatically.
  private val graphite = new Graphite(new InetSocketAddress(host, port))

  private val reporter = GraphiteReporter
    .forRegistry(registry)
    .prefixedWith(prefix)
    .convertRatesTo(TimeUnit.SECONDS)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .build(graphite)

  // Start the background flush thread. If Graphite is down it logs a warning
  // and retries next interval — the app keeps running.
  reporter.start(interval, TimeUnit.SECONDS)
  logger.info(s"[Metrics] GraphiteReporter started — flushing to $host:$port every ${interval}s (prefix=$prefix)")

  lifecycle.addStopHook { () =>
    Future {
      reporter.stop()
      logger.info("[Metrics] GraphiteReporter stopped")
    }
  }

  def incrementParcelCreated(): Unit = {
    registry.counter("parcels.created").inc()
    logger.debug("[Metrics] parcels.created +1")
  }

  // Status name is lowercased so "IN_TRANSIT" becomes parcels.status_change.in_transit in Graphite.
  // That keeps metric names consistent regardless of how callers capitalise the status.
  def incrementStatusChange(newStatus: String): Unit = {
    registry.counter(s"parcels.status_change.${newStatus.toLowerCase}").inc()
    logger.debug(s"[Metrics] parcels.status_change.${newStatus.toLowerCase} +1")
  }
}
