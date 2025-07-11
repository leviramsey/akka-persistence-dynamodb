/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.dynamodb.internal

import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionException
import java.util.Base64
import java.util.Locale
import java.util.{ HashMap => JHashMap }
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.util.control.NonFatal

import akka.Done
import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.persistence.Persistence
import akka.persistence.dynamodb.DynamoDBSettings
import akka.persistence.typed.PersistenceId
import akka.serialization.SerializationExtension
import akka.serialization.Serializers
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.SdkResponse
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.Delete
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest
import software.amazon.awssdk.services.dynamodb.model.Update

/**
 * INTERNAL API
 */
@InternalApi private[akka] object JournalDao {
  private val log: Logger = LoggerFactory.getLogger(classOf[JournalDao])

  private val base64Encoder = Base64.getEncoder
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class JournalDao(
    system: ActorSystem[_],
    settings: DynamoDBSettings,
    client: DynamoDbAsyncClient) {
  import JournalDao._

  private val persistenceExt: Persistence = Persistence(system)
  private val serialization = SerializationExtension(system)
  private val fallbackStoreProvider = FallbackStoreProvider(system)

  private implicit val ec: ExecutionContext = system.executionContext

  private val dateHeaderLogCounter = new AtomicLong
  private val dateHeaderParser = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
  private val clockSkewToleranceMillis = settings.clockSkewSettings.warningTolerance.toMillis

  private def checkClockSkew(response: SdkResponse): Unit = {
    try {
      if (clockSkewToleranceMillis > 0 &&
        dateHeaderLogCounter.getAndIncrement() % 1000 == 0) {
        val dateHeaderOpt = response.sdkHttpResponse().firstMatchingHeader("Date")
        if (dateHeaderOpt.isPresent) {
          val dateHeader = dateHeaderOpt.get
          val awsInstant = Instant.from(dateHeaderParser.parse(dateHeader))
          val now = Instant.now()
          // The Date header only has precision of seconds so this is just a rough check
          if (math.abs(java.time.Duration.between(awsInstant, now).toMillis) >= clockSkewToleranceMillis) {
            log.warn(
              "Possible clock skew, make sure clock synchronization is installed. " +
              "Local time [{}] vs DynamoDB response time [{}]",
              now,
              awsInstant)
          }
        }
      }
    } catch {
      case NonFatal(exc) =>
        log.warn("check clock skew failed", exc)
    }

  }

  def writeEvents(events: Seq[SerializedJournalItem]): Future[Done] = {
    require(events.nonEmpty)

    // it's always the same persistenceId for all events
    val persistenceId = events.head.persistenceId
    val entityType = PersistenceId.extractEntityType(persistenceId)

    val timeToLiveSettings = settings.timeToLiveSettings.eventSourcedEntities.get(entityType)
    val ttlEnabled = timeToLiveSettings.eventTimeToLive.exists(_ > Duration.Zero)

    val estimatedTotalSize = events.foldLeft(0)(_ + _.estimatedDynamoSize(entityType, ttlEnabled))

    if (settings.journalFallbackSettings.isEnabled && estimatedTotalSize > settings.journalFallbackSettings.threshold) {
      val fallbackStore = fallbackStoreProvider.eventFallbackStoreFor(settings.journalFallbackSettings.plugin)
      implicit val mat: ActorSystem[_] = system

      Source(events)
        .mapAsync(settings.journalFallbackSettings.batchSize) { event =>
          fallbackStore
            .saveEvent(persistenceId, event.seqNr, event.serId, event.serManifest, event.payload.get)
            .map(_ -> event)(ExecutionContext.parasitic)
        }
        .runWith(Sink.seq)
        .flatMap { writtenEvents =>
          // All have been saved to S3, so we can save the breadcrumbs
          val withBreadcrumbs = writtenEvents.map { case (breadcrumb, evt) =>
            val bytes = serialization.serialize(breadcrumb).get
            val serializer = serialization.findSerializerFor(breadcrumb)
            val manifest = Serializers.manifestFor(serializer, breadcrumb)

            JournalItemWithBreadcrumb(
              persistenceId = persistenceId,
              seqNr = evt.seqNr,
              writeTimestamp = evt.writeTimestamp,
              readTimestamp = evt.readTimestamp,
              writerUuid = evt.writerUuid,
              tags = evt.tags,
              metadata = evt.metadata,
              breadcrumbSerId = serializer.identifier,
              breadcrumbSerManifest = manifest,
              breadcrumbPayload = Some(bytes))
          }

          writeItems(withBreadcrumbs)
        }

    } else writeItems(events)
  }

  private def writeItems(items: Seq[ItemInJournal]): Future[Done] = {
    require(items.nonEmpty)

    // always the same persistenceId
    val persistenceId = items.head.persistenceId
    val entityType = PersistenceId.extractEntityType(persistenceId)
    val slice = persistenceExt.sliceForPersistenceId(persistenceId)

    val timeToLiveSettings = settings.timeToLiveSettings.eventSourcedEntities.get(entityType)

    def putItemAttributes(item: ItemInJournal) = {
      import JournalAttributes._

      val attributes = new JHashMap[String, AttributeValue]
      attributes.put(Pid, AttributeValue.fromS(persistenceId))
      attributes.put(SeqNr, AttributeValue.fromN(item.seqNr.toString))
      attributes.put(EntityTypeSlice, AttributeValue.fromS(s"$entityType-$slice"))

      val timestampMicros = InstantFactory.toEpochMicros(item.writeTimestamp)
      attributes.put(Timestamp, AttributeValue.fromN(timestampMicros.toString))
      attributes.put(Writer, AttributeValue.fromS(item.writerUuid))

      if (item.tags.nonEmpty) { // empty sets not supported by DynamoDB
        attributes.put(Tags, AttributeValue.fromSs(item.tags.toSeq.asJava))
      }

      item.metadata.foreach { meta =>
        attributes.put(MetaSerId, AttributeValue.fromN(meta.serId.toString))
        attributes.put(MetaSerManifest, AttributeValue.fromS(meta.serManifest))
        attributes.put(MetaPayload, AttributeValue.fromB(SdkBytes.fromByteArray(meta.payload)))
      }

      timeToLiveSettings.eventTimeToLive.foreach { timeToLive =>
        val expiryTimestamp = item.writeTimestamp.plusSeconds(timeToLive.toSeconds)
        attributes.put(Expiry, AttributeValue.fromN(expiryTimestamp.getEpochSecond.toString))
      }

      item match {
        case sji: SerializedJournalItem =>
          attributes.put(EventSerId, AttributeValue.fromN(sji.serId.toString))
          attributes.put(EventSerManifest, AttributeValue.fromS(sji.serManifest))
          attributes.put(EventPayload, AttributeValue.fromB(SdkBytes.fromByteArray(sji.payload.get)))

        case jiwb: JournalItemWithBreadcrumb =>
          attributes.put(BreadcrumbSerId, AttributeValue.fromN(jiwb.breadcrumbSerId.toString))
          attributes.put(BreadcrumbSerManifest, AttributeValue.fromS(jiwb.breadcrumbSerManifest))
          attributes.put(BreadcrumbPayload, AttributeValue.fromB(SdkBytes.fromByteArray(jiwb.breadcrumbPayload.get)))
      }

      attributes
    }

    val totalItems = items.size

    if (totalItems == 1) {
      val item = items.head
      val result = client.putItem { putItemBuilder =>
        putItemBuilder
          .tableName(settings.journalTable)
          .item(putItemAttributes(item))
          .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
      }.asScala

      if (log.isDebugEnabled) {
        result.foreach { response =>
          val logBase =
            item match {
              case sji: SerializedJournalItem      => "Wrote [1] events "
              case jiwb: JournalItemWithBreadcrumb => "Wrote [1] events with breadcrumbs "
            }
          log.debug(
            logBase + s"for persistenceId [$persistenceId] consumed [${response.consumedCapacity.capacityUnits}] WCU")
        }
      }

      result
        .map { response =>
          checkClockSkew(response)
          Done
        }(ExecutionContext.parasitic)
        .recoverWith { case c: CompletionException =>
          Future.failed(c.getCause)
        }(ExecutionContext.parasitic)
    } else {
      val writeItems = items.map { item =>
        TransactWriteItem
          .builder()
          .put { putBuilder =>
            putBuilder.tableName(settings.journalTable).item(putItemAttributes(item)).build
          }
          .build
      }.asJava

      val token = {
        val firstItem = items.head
        val uuid = UUID.fromString(firstItem.writerUuid)
        val seqNr = firstItem.seqNr
        val bb = ByteBuffer.allocate(24)
        bb.asLongBuffer().put(uuid.getMostSignificantBits).put(uuid.getLeastSignificantBits).put(seqNr)

        new String(base64Encoder.encode(bb.array))
      }

      val req = TransactWriteItemsRequest
        .builder()
        .clientRequestToken(token)
        .transactItems(writeItems)
        .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
        .build

      val result = client.transactWriteItems(req).asScala

      if (log.isDebugEnabled) {
        result.foreach { response =>
          val logBase =
            items.head match {
              case sji: SerializedJournalItem      => s"Wrote [$totalItems] events "
              case jiwb: JournalItemWithBreadcrumb => s"Wrote [$totalItems] events with breadcrumbs "
            }

          val capacityConsumed = response.consumedCapacity.iterator.asScala.map(_.capacityUnits.doubleValue).sum

          log.debug(logBase + s"for persistenceId [$persistenceId] consumed [$capacityConsumed] WCU")
        }
      }

      result
        .map { response =>
          checkClockSkew(response)
          Done
        }(ExecutionContext.parasitic)
        .recoverWith { case c: CompletionException =>
          Future.failed(c.getCause)
        }(ExecutionContext.parasitic)
    }
  }

  def readHighestSequenceNr(persistenceId: String): Future[Long] = {
    readHighestSequenceNrAndTimestamp(persistenceId).map(_._1)(ExecutionContext.parasitic)
  }

  def readHighestSequenceNrAndTimestamp(persistenceId: String): Future[(Long, Instant)] = {
    import JournalAttributes._

    val attributeValues = Map(":pid" -> AttributeValue.fromS(persistenceId))

    val entityType = PersistenceId.extractEntityType(persistenceId)
    val timeToLiveSettings = settings.timeToLiveSettings.eventSourcedEntities.get(entityType)

    val (filterExpression, filterAttributeValues) =
      if (timeToLiveSettings.checkExpiry) {
        val now = System.currentTimeMillis / 1000
        val expression = s"attribute_not_exists($Expiry) OR $Expiry > :now"
        val attributes = Map(":now" -> AttributeValue.fromN(now.toString))
        (Some(expression), attributes)
      } else (None, Map.empty[String, AttributeValue])

    val requestBuilder = QueryRequest.builder
      .tableName(settings.journalTable)
      .consistentRead(true)
      .keyConditionExpression(s"$Pid = :pid")
      .expressionAttributeValues((attributeValues ++ filterAttributeValues).asJava)
      .projectionExpression(s"$SeqNr, $Timestamp")
      .scanIndexForward(false) // get last item (highest sequence nr)
      .limit(1)

    filterExpression.foreach(requestBuilder.filterExpression)

    val result = client.query(requestBuilder.build()).asScala.map { response =>
      response.items().asScala.headOption.fold((0L, Instant.EPOCH)) { item =>
        (item.get(SeqNr).n().toLong, InstantFactory.fromEpochMicros(item.get(Timestamp).n().toLong))
      }
    }

    if (log.isDebugEnabled)
      result.foreach { case (seqNr, timestamp) =>
        log.debug("Highest sequence nr for persistenceId [{}]: [{}] (written at [{}])", persistenceId, seqNr, timestamp)
      }

    result
      .recoverWith { case c: CompletionException =>
        Future.failed(c.getCause)
      }(ExecutionContext.parasitic)
  }

  private def readLowestSequenceNr(persistenceId: String): Future[Long] = {
    import JournalAttributes._

    val attributeValues = Map(":pid" -> AttributeValue.fromS(persistenceId)).asJava

    val request = QueryRequest.builder
      .tableName(settings.journalTable)
      .consistentRead(true)
      .keyConditionExpression(s"$Pid = :pid")
      .expressionAttributeValues(attributeValues)
      .projectionExpression(s"$SeqNr")
      .scanIndexForward(true) // get first item (lowest sequence nr)
      .limit(1)
      .build()

    val result = client.query(request).asScala.map { response =>
      response.items().asScala.headOption.fold(0L) { item =>
        item.get(SeqNr).n().toLong
      }
    }

    if (log.isDebugEnabled)
      result.foreach(seqNr => log.debug("Lowest sequence nr for persistenceId [{}]: [{}]", persistenceId, seqNr))

    result
      .recoverWith { case c: CompletionException =>
        Future.failed(c.getCause)
      }(ExecutionContext.parasitic)
  }

  def deleteEventsTo(persistenceId: String, toSequenceNr: Long, resetSequenceNumber: Boolean): Future[Unit] = {
    import JournalAttributes._

    def pk(pid: String, seqNr: Long): JHashMap[String, AttributeValue] = {
      val m = new JHashMap[String, AttributeValue]
      m.put(Pid, AttributeValue.fromS(pid))
      m.put(SeqNr, AttributeValue.fromN(seqNr.toString))
      m
    }

    def deleteBatch(from: Long, to: Long, lastBatch: Boolean): Future[Unit] = {
      val result = {
        val toSeqNr = if (lastBatch && !resetSequenceNumber) to - 1 else to
        val deleteItems =
          (from to toSeqNr).map { seqNr =>
            TransactWriteItem
              .builder()
              .delete(Delete.builder().tableName(settings.journalTable).key(pk(persistenceId, seqNr)).build())
              .build()
          }

        val writeItems =
          if (lastBatch && !resetSequenceNumber) {
            // update last item instead of deleting, keeping it as a tombstone to keep track of latest seqNr even
            // though all events have been deleted
            val deleteMarker =
              TransactWriteItem
                .builder()
                .update(Update
                  .builder()
                  .tableName(settings.journalTable)
                  .key(pk(persistenceId, to))
                  .updateExpression(
                    s"SET $Deleted = :del REMOVE $EventPayload, $EventSerId, $EventSerManifest, $Writer, $MetaPayload, $MetaSerId, $MetaSerManifest")
                  .expressionAttributeValues(Map(":del" -> AttributeValue.fromBool(true)).asJava)
                  .build())
                .build()
            deleteItems :+ deleteMarker
          } else
            deleteItems

        val req = TransactWriteItemsRequest
          .builder()
          .transactItems(writeItems.asJava)
          .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
          .build()

        client.transactWriteItems(req).asScala
      }

      if (log.isDebugEnabled()) {
        result.foreach { response =>
          log.debug(
            "Deleted events from [{}] to [{}] for persistenceId [{}], consumed [{}] WCU",
            from,
            to,
            persistenceId,
            response.consumedCapacity.iterator.asScala.map(_.capacityUnits.doubleValue()).sum)
        }
      }
      result
        .map(_ => ())(ExecutionContext.parasitic)
        .recoverWith { case c: CompletionException =>
          Future.failed(c.getCause)
        }(ExecutionContext.parasitic)
    }

    // TransactWriteItems has a limit of 100
    val batchSize = 100

    def deleteInBatches(from: Long, maxTo: Long): Future[Unit] = {
      if (from + batchSize > maxTo) {
        deleteBatch(from, maxTo, lastBatch = true)
      } else {
        val to = from + batchSize - 1
        deleteBatch(from, to, lastBatch = false).flatMap(_ => deleteInBatches(to + 1, maxTo))
      }
    }

    val lowestSequenceNrForDelete = readLowestSequenceNr(persistenceId)
    val highestSeqNrForDelete =
      if (toSequenceNr == Long.MaxValue) readHighestSequenceNr(persistenceId)
      else Future.successful(toSequenceNr)

    val result =
      for {
        fromSeqNr <- lowestSequenceNrForDelete
        toSeqNr <- highestSeqNrForDelete
        _ <- deleteInBatches(fromSeqNr, toSeqNr)
      } yield ()

    result
      .recoverWith { case c: CompletionException =>
        Future.failed(c.getCause)
      }(ExecutionContext.parasitic)
  }

  def updateEventExpiry(
      persistenceId: String,
      toSequenceNr: Long,
      resetSequenceNumber: Boolean,
      expiryTimestamp: Instant): Future[Unit] = {
    import JournalAttributes._

    def pk(pid: String, seqNr: Long): JHashMap[String, AttributeValue] = {
      val m = new JHashMap[String, AttributeValue]
      m.put(Pid, AttributeValue.fromS(pid))
      m.put(SeqNr, AttributeValue.fromN(seqNr.toString))
      m
    }

    def updateBatch(fromSeqNr: Long, toSeqNr: Long, lastBatch: Boolean): Future[Unit] = {
      val result = {
        val expireItems =
          (fromSeqNr to toSeqNr).map { seqNr =>
            // when not resetting sequence number, only mark last item with expiry, keeping it to track latest
            val updateExpression =
              if (lastBatch && !resetSequenceNumber && seqNr == toSeqNr) s"SET $ExpiryMarker = :expiry REMOVE $Expiry"
              else s"SET $Expiry = :expiry REMOVE $ExpiryMarker"

            TransactWriteItem.builder
              .update(
                Update.builder
                  .tableName(settings.journalTable)
                  .key(pk(persistenceId, seqNr))
                  .updateExpression(updateExpression)
                  .expressionAttributeValues(
                    Map(":expiry" -> AttributeValue.fromN(expiryTimestamp.getEpochSecond.toString)).asJava)
                  .build())
              .build()
          }

        val request = TransactWriteItemsRequest
          .builder()
          .transactItems(expireItems.asJava)
          .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
          .build()

        client.transactWriteItems(request).asScala
      }

      if (log.isDebugEnabled()) {
        result.foreach { response =>
          log.debug(
            "Updated expiry of events for persistenceId [{}], for sequence numbers [{}] to [{}], expiring at [{}], consumed [{}] WCU",
            persistenceId,
            fromSeqNr,
            toSeqNr,
            expiryTimestamp,
            response.consumedCapacity.iterator.asScala.map(_.capacityUnits.doubleValue()).sum)
        }
      }
      result
        .map(_ => ())(ExecutionContext.parasitic)
        .recoverWith { case c: CompletionException =>
          Future.failed(c.getCause)
        }(ExecutionContext.parasitic)
    }

    // TransactWriteItems has a limit of 100
    val batchSize = 100

    def updateInBatches(from: Long, maxTo: Long): Future[Unit] = {
      if (from + batchSize > maxTo) {
        updateBatch(from, maxTo, lastBatch = true)
      } else {
        val to = from + batchSize - 1
        updateBatch(from, to, lastBatch = false).flatMap(_ => updateInBatches(to + 1, maxTo))
      }
    }

    val lowestSequenceNr = readLowestSequenceNr(persistenceId)
    val highestSeqNr =
      if (toSequenceNr == Long.MaxValue) readHighestSequenceNr(persistenceId)
      else Future.successful(toSequenceNr)

    val result =
      for {
        fromSeqNr <- lowestSequenceNr
        toSeqNr <- highestSeqNr
        _ <- updateInBatches(fromSeqNr, toSeqNr)
      } yield ()

    result
      .recoverWith { case c: CompletionException =>
        Future.failed(c.getCause)
      }(ExecutionContext.parasitic)
  }
}
