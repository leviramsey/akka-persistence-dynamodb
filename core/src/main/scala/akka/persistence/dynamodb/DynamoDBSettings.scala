/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.dynamodb

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._

import akka.actor.typed.ActorSystem
import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.annotation.InternalStableApi
import akka.util.Helpers
import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.retry.RetryMode

object DynamoDBSettings {

  private[akka] val log = LoggerFactory.getLogger(classOf[DynamoDBSettings])

  /**
   * Scala API: Load configuration from `akka.persistence.dynamodb`.
   */
  def apply(system: ActorSystem[_]): DynamoDBSettings =
    apply(system.settings.config.getConfig("akka.persistence.dynamodb"))

  /**
   * Java API: Load configuration from `akka.persistence.dynamodb`.
   */
  def create(system: ActorSystem[_]): DynamoDBSettings =
    apply(system)

  /**
   * Scala API: From custom configuration corresponding to `akka.persistence.dynamodb`.
   */
  def apply(config: Config): DynamoDBSettings = {
    val journalTable: String = config.getString("journal.table")

    val journalPublishEvents: Boolean = config.getBoolean("journal.publish-events")

    val snapshotTable: String = config.getString("snapshot.table")

    val querySettings = new QuerySettings(config.getConfig("query"))

    val cleanupSettings = new CleanupSettings(config.getConfig("cleanup"))

    val timeToLiveSettings = new TimeToLiveSettings(config.getConfig("time-to-live"))

    val journalBySliceGsi = {
      val indexName = config.getString("journal.by-slice-idx")
      if (indexName.nonEmpty) indexName else journalTable + "_slice_idx"
    }

    val snapshotBySliceGsi = {
      val indexName = config.getString("snapshot.by-slice-idx")
      if (indexName.nonEmpty) indexName else snapshotTable + "_slice_idx"
    }

    val clockSkewSettings = new ClockSkewSettings(config)

    val journalFallbackSettings = JournalFallbackSettings(config.getConfig("journal.fallback-store"))

    val snapshotFallbackSettings = SnapshotFallbackSettings(config.getConfig("snapshot.fallback-store"))

    new DynamoDBSettings(
      journalTable,
      journalPublishEvents,
      snapshotTable,
      querySettings,
      cleanupSettings,
      timeToLiveSettings,
      journalBySliceGsi,
      snapshotBySliceGsi,
      clockSkewSettings,
      journalFallbackSettings,
      snapshotFallbackSettings)
  }

  /**
   * Java API: From custom configuration corresponding to `akka.persistence.dynamodb`.
   */
  def create(config: Config): DynamoDBSettings =
    apply(config)

}

/**
 * Use `DynamoDBSettings.apply` or `DynamoDBSettings.create` for construction.
 */
final class DynamoDBSettings private (
    val journalTable: String,
    val journalPublishEvents: Boolean,
    val snapshotTable: String,
    val querySettings: QuerySettings,
    val cleanupSettings: CleanupSettings,
    val timeToLiveSettings: TimeToLiveSettings,
    val journalBySliceGsi: String,
    val snapshotBySliceGsi: String,
    val clockSkewSettings: ClockSkewSettings,
    val journalFallbackSettings: JournalFallbackSettings,
    val snapshotFallbackSettings: SnapshotFallbackSettings) {
  override def toString: String =
    s"DynamoDBSettings(journalTable=$journalTable, journalPublishEvents=$journalPublishEvents, " +
    s"snapshotTable=$snapshotTable, querySettings=$querySettings, cleanupSettings=$cleanupSettings, " +
    s"timeToLiveSettings=$timeToLiveSettings, journalBySliceGsi=$journalBySliceGsi, " +
    s"snapshotBySliceGsi=$snapshotBySliceGsi, clockSkewSettings=$clockSkewSettings, " +
    s"journalFallbackSettings=$journalFallbackSettings, snapshotFallbackSettings=$snapshotFallbackSettings)"
}

final class QuerySettings(config: Config) {
  val refreshInterval: FiniteDuration = config.getDuration("refresh-interval").toScala
  val behindCurrentTime: FiniteDuration = config.getDuration("behind-current-time").toScala
  val backtrackingEnabled: Boolean = config.getBoolean("backtracking.enabled")
  val backtrackingWindow: FiniteDuration = config.getDuration("backtracking.window").toScala
  val backtrackingBehindCurrentTime: FiniteDuration = config.getDuration("backtracking.behind-current-time").toScala
  val bufferSize: Int = config.getInt("buffer-size")
  val deduplicateCapacity: Int = config.getInt("deduplicate-capacity")
  val startFromSnapshotEnabled: Boolean = config.getBoolean("start-from-snapshot.enabled")
}

object ClientSettings {
  final class HttpSettings(
      val maxConcurrency: Int,
      val maxPendingConnectionAcquires: Int,
      val readTimeout: FiniteDuration,
      val writeTimeout: FiniteDuration,
      val connectionTimeout: FiniteDuration,
      val connectionAcquisitionTimeout: FiniteDuration,
      val connectionTimeToLive: FiniteDuration,
      val useIdleConnectionReaper: Boolean,
      val connectionMaxIdleTime: FiniteDuration,
      val tlsNegotiationTimeout: FiniteDuration,
      val tcpKeepAlive: Boolean) {

    override def toString: String =
      s"HttpSettings(" +
      s"maxConcurrency=$maxConcurrency, " +
      s"maxPendingConnectionAcquires=$maxPendingConnectionAcquires, " +
      s"readTimeout=${readTimeout.toCoarsest}, " +
      s"writeTimeout=${writeTimeout.toCoarsest}, " +
      s"connectionTimeout=${connectionTimeout.toCoarsest}, " +
      s"connectionAcquisitionTimeout=${connectionAcquisitionTimeout.toCoarsest}, " +
      s"connectionTimeToLive=${connectionTimeToLive.toCoarsest}, " +
      s"useIdleConnectionReaper=$useIdleConnectionReaper, " +
      s"connectionMaxIdleTime=${connectionMaxIdleTime.toCoarsest}, " +
      s"tlsNegotiationTimeout=${tlsNegotiationTimeout.toCoarsest}, " +
      s"tcpKeepAlive=$tcpKeepAlive)"
  }

  object HttpSettings {
    def apply(clientConfig: Config): HttpSettings = {
      val config = clientConfig.getConfig("http")
      new HttpSettings(
        maxConcurrency = config.getInt("max-concurrency"),
        maxPendingConnectionAcquires = config.getInt("max-pending-connection-acquires"),
        readTimeout = config.getDuration("read-timeout").toScala,
        writeTimeout = config.getDuration("write-timeout").toScala,
        connectionTimeout = config.getDuration("connection-timeout").toScala,
        connectionAcquisitionTimeout = config.getDuration("connection-acquisition-timeout").toScala,
        connectionTimeToLive = config.getDuration("connection-time-to-live").toScala,
        useIdleConnectionReaper = config.getBoolean("use-idle-connection-reaper"),
        connectionMaxIdleTime = config.getDuration("connection-max-idle-time").toScala,
        tlsNegotiationTimeout = config.getDuration("tls-negotiation-timeout").toScala,
        tcpKeepAlive = config.getBoolean("tcp-keep-alive"))
    }
  }

  final class RetrySettings(val mode: RetryMode, val maxAttempts: Option[Int]) {
    override def toString: String =
      s"RetrySettings(" +
      s"mode=$mode, " +
      s"maxAttempts=${maxAttempts.fold("default")(_.toString)})"
  }

  object RetrySettings {
    def get(clientConfig: Config): Option[RetrySettings] = {
      if (clientConfig.hasPath("retry-policy")) {
        DynamoDBSettings.log.warn(
          "Configuration for `akka.persistence.dynamodb.client.retry-policy` is deprecated. Use `retry-strategy` instead.")
      }
      val config = clientConfig.getConfig("retry-strategy")
      // prefer and adapt deprecated retry-policy settings if they exist
      val enabled =
        if (clientConfig.hasPath("retry-policy.enabled")) clientConfig.getBoolean("retry-policy.enabled")
        else config.getBoolean("enabled")
      val retryMode =
        if (clientConfig.hasPath("retry-policy.retry-mode")) clientConfig.getString("retry-policy.retry-mode")
        else config.getString("retry-mode")
      val maxAttempts =
        if (clientConfig.hasPath("retry-policy.num-retries"))
          ConfigHelpers.optInt(clientConfig, "retry-policy.num-retries").map(_ + 1)
        else ConfigHelpers.optInt(config, "max-attempts")
      if (enabled) {
        val mode = Helpers.toRootLowerCase(retryMode) match {
          case "default"  => RetryMode.defaultRetryMode()
          case "legacy"   => RetryMode.LEGACY
          case "standard" => RetryMode.STANDARD
          case "adaptive" => RetryMode.ADAPTIVE_V2
        }
        Some(new RetrySettings(mode = mode, maxAttempts = maxAttempts))
      } else None
    }
  }

  final class CompressionSettings(val enabled: Boolean, val thresholdBytes: Int) {
    override def toString: String =
      s"CompressionSettings(" +
      s"enabled=$enabled, " +
      s"thresholdBytes=$thresholdBytes)"
  }

  object CompressionSettings {
    def apply(clientConfig: Config): CompressionSettings = {
      val config = clientConfig.getConfig("compression")
      new CompressionSettings(
        enabled = config.getBoolean("enabled"),
        thresholdBytes = config.getBytes("threshold").toInt)
    }
  }

  final class LocalSettings(val host: String, val port: Int) {
    override def toString = s"LocalSettings(host=$host, port=$port)"
  }

  object LocalSettings {
    def get(clientConfig: Config): Option[LocalSettings] = {
      val config = clientConfig.getConfig("local")
      if (config.getBoolean("enabled")) {
        Some(new LocalSettings(config.getString("host"), config.getInt("port")))
      } else None
    }
  }

  def apply(config: Config): ClientSettings = {
    new ClientSettings(
      callTimeout = config.getDuration("call-timeout").toScala,
      callAttemptTimeout = ConfigHelpers.optDuration(config, "call-attempt-timeout"),
      http = HttpSettings(config),
      retry = RetrySettings.get(config),
      compression = CompressionSettings(config),
      region = ConfigHelpers.optString(config, "region"),
      local = LocalSettings.get(config))
  }
}

final class ClientSettings(
    val callTimeout: FiniteDuration,
    val callAttemptTimeout: Option[FiniteDuration],
    val http: ClientSettings.HttpSettings,
    val retry: Option[ClientSettings.RetrySettings],
    val compression: ClientSettings.CompressionSettings,
    val region: Option[String],
    val local: Option[ClientSettings.LocalSettings]) {

  override def toString: String =
    s"ClientSettings(" +
    s"callTimeout=${callTimeout.toCoarsest}, " +
    s"callAttemptTimeout=${callAttemptTimeout.map(_.toCoarsest)}, " +
    s"http=$http, " +
    s"retry=$retry, " +
    s"compression=$compression, " +
    s"region=$region, " +
    s"local=$local)"
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class PublishEventsDynamicSettings(config: Config) {
  val throughputThreshold: Int = config.getInt("throughput-threshold")
  val throughputCollectInterval: FiniteDuration = config.getDuration("throughput-collect-interval").toScala
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class CleanupSettings(config: Config) {
  val logProgressEvery: Int = config.getInt("log-progress-every")
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class TimeToLiveSettings(config: Config) {
  val eventSourcedEntities: WildcardMap[EventSourcedEntityTimeToLiveSettings] = {
    val defaults = config.getConfig("event-sourced-defaults")
    val defaultSettings = new EventSourcedEntityTimeToLiveSettings(defaults)
    val entries = config.getConfig("event-sourced-entities").root.entrySet.asScala
    val perEntitySettings = entries.toSeq.flatMap { entry =>
      (entry.getKey, entry.getValue) match {
        case (key: String, value: ConfigObject) =>
          val settings = new EventSourcedEntityTimeToLiveSettings(value.toConfig.withFallback(defaults))
          Some(key -> settings)
        case _ => None
      }
    }
    WildcardMap(perEntitySettings, defaultSettings)
  }
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class EventSourcedEntityTimeToLiveSettings(config: Config) {
  val checkExpiry: Boolean = config.getBoolean("check-expiry")

  val useTimeToLiveForDeletes: Option[FiniteDuration] =
    ConfigHelpers.optDuration(config, "use-time-to-live-for-deletes")

  val eventTimeToLive: Option[FiniteDuration] = ConfigHelpers.optDuration(config, "event-time-to-live")

  val snapshotTimeToLive: Option[FiniteDuration] = ConfigHelpers.optDuration(config, "snapshot-time-to-live")
}

/**
 * INTERNAL API
 */
@InternalStableApi
final class ClockSkewSettings(config: Config) {
  val warningTolerance: FiniteDuration = {
    val path = "clock-skew-detection.warning-tolerance"
    Helpers.toRootLowerCase(config.getString(path)) match {
      case "off" | "none" => Duration.Zero
      case _              => config.getDuration(path).toScala
    }
  }

  override def toString: String = s"ClockSkewSettings($warningTolerance)"
}

/** Not for user extension */
@DoNotInherit
sealed abstract class FallbackSettings(val plugin: String, val threshold: Int) {
  require(threshold > 0, "threshold must be positive")
  require(threshold <= (400 * 1000), "threshold must be at most 400 KB")

  // Must be overridden in subclasses
  override def toString: String = throw new scala.NotImplementedError

  def isEnabled: Boolean = plugin.nonEmpty
}

@ApiMayChange
final class SnapshotFallbackSettings(plugin: String, threshold: Int) extends FallbackSettings(plugin, threshold) {
  override def toString: String =
    if (isEnabled)
      s"SnapshotFallbackSettings(plugin=$plugin, threshold=${threshold}B)"
    else "disabled"
}

object SnapshotFallbackSettings {
  def apply(config: Config): SnapshotFallbackSettings = {
    val plugin = config.getString("plugin")
    val threshold = java.lang.Long.min(config.getBytes("threshold"), 400 * 1000).toInt

    new SnapshotFallbackSettings(plugin, threshold)
  }
}

@ApiMayChange
final class JournalFallbackSettings(commonSettings: SnapshotFallbackSettings, val batchSize: Int)
    extends FallbackSettings(commonSettings.plugin, commonSettings.threshold) {
  require(!commonSettings.isEnabled || batchSize > 0, "batch size must be positive")

  override def toString: String =
    if (isEnabled)
      s"JournalFallbackSettings(plugin=$plugin, threshold=${threshold}B, batchSize=$batchSize)"
    else "disabled"
}

object JournalFallbackSettings {
  def apply(config: Config): JournalFallbackSettings = {
    val commonSettings = SnapshotFallbackSettings(config)
    val batchSize = config.getInt("batch-size")

    new JournalFallbackSettings(commonSettings, batchSize)
  }
}

private[akka] object ConfigHelpers {
  def optString(config: Config, path: String): Option[String] = {
    if (config.hasPath(path)) {
      val value = config.getString(path)
      if (value.nonEmpty) Some(value) else None
    } else None
  }

  def optDuration(config: Config, path: String): Option[FiniteDuration] = {
    Helpers.toRootLowerCase(config.getString(path)) match {
      case "off" | "none" => None
      case _              => Some(config.getDuration(path).toScala)
    }
  }

  def optInt(config: Config, path: String): Option[Int] = {
    Helpers.toRootLowerCase(config.getString(path)) match {
      case "default" => None
      case _         => Some(config.getInt(path))
    }
  }
}

private[akka] object WildcardMap {
  def apply[V](elements: Seq[(String, V)], default: V): WildcardMap[V] = {
    val (wildcards, exact) = elements.partition { case (key, _) => hasWildcard(key) }
    val prefixes = wildcards.map { case (key, value) => dropWildcard(key) -> value }
    new WildcardMap[V](exact.toMap, prefixes.toMap, default)
  }

  private def hasWildcard(key: String): Boolean = key.endsWith("*")

  private def dropWildcard(key: String): String = key.dropRight(1)
}

private[akka] final class WildcardMap[V](exact: Map[String, V], prefixes: Map[String, V], default: V) {
  import WildcardMap._

  def isEmpty: Boolean = exact.isEmpty && prefixes.isEmpty

  def get(key: String): V = {
    if (isEmpty) default
    else
      exact
        .get(key)
        .orElse(prefixes.collectFirst { case (k, v) if key.startsWith(k) => v })
        .getOrElse(default)
  }

  def updated(key: String, value: V): WildcardMap[V] = {
    if (hasWildcard(key)) new WildcardMap(exact, prefixes.updated(dropWildcard(key), value), default)
    else new WildcardMap(exact.updated(key, value), prefixes, default)
  }
}
