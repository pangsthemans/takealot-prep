package services

import com.google.api.core.{ApiFutureCallback, ApiFutures}
import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{AlreadyExistsException, FixedTransportChannelProvider}
import com.google.cloud.pubsub.v1.{Publisher, TopicAdminClient, TopicAdminSettings}
import com.google.common.util.concurrent.MoreExecutors
import com.google.protobuf.ByteString
import com.google.pubsub.v1.{PubsubMessage, TopicName}
import io.grpc.ManagedChannelBuilder
import models.Parcel
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import play.api.{Configuration, Logger}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

// ExecutionContext is Scala's thread pool — needed to create Futures.
// It's an implicit parameter: Guice injects it; callers don't pass it by hand.
// ApplicationLifecycle lets us register a shutdown hook so the publisher flushes
// in-flight messages before the JVM exits.
@Singleton
class ParcelEventPublisher @Inject()(
  config: Configuration,
  lifecycle: ApplicationLifecycle
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  private val projectId    = config.get[String]("pubsub.project")
  private val topicId      = config.get[String]("pubsub.topic")
  private val topicName    = TopicName.of(projectId, topicId)
  private val emulatorHost = config.get[String]("pubsub.emulatorHost")

  // Open a plaintext gRPC channel to the emulator.
  // In production you'd remove this and let the client use TLS + ADC credentials.
  private val channel =
    ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build()

  private val channelProvider =
    FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))

  private val credentialsProvider = NoCredentialsProvider.create()

  // Create the topic if it doesn't exist yet.
  // TopicAdminClient is AutoCloseable (Java), so we close it in a finally block.
  locally {
    val adminSettings = TopicAdminSettings.newBuilder()
      .setTransportChannelProvider(channelProvider)
      .setCredentialsProvider(credentialsProvider)
      .build()
    val admin = TopicAdminClient.create(adminSettings)
    try {
      admin.createTopic(topicName)
      logger.info(s"Created Pub/Sub topic '$topicId'")
    } catch {
      case _: AlreadyExistsException =>
        logger.info(s"Pub/Sub topic '$topicId' already exists")
      case e: Exception =>
        logger.warn(s"Could not ensure Pub/Sub topic exists: ${e.getMessage}")
    } finally {
      admin.close()
    }
  }

  private val publisher: Publisher = Publisher
    .newBuilder(topicName)
    .setChannelProvider(channelProvider)
    .setCredentialsProvider(credentialsProvider)
    .build()

  // Graceful shutdown: flush any buffered messages and close the gRPC channel.
  // addStopHook takes a () => Future[_]; Play awaits it before killing the process.
  lifecycle.addStopHook { () =>
    Future {
      publisher.shutdown()
      channel.shutdown()
    }
  }

  def publish(parcel: Parcel): Unit = {
    val payload = Json.stringify(Json.obj(
      "eventType"  -> "STATUS_CHANGED",
      "parcelId"   -> parcel.id,
      "newStatus"  -> parcel.currentStatus,
      "occurredAt" -> parcel.updatedAt.toString
    ))
    val message = PubsubMessage.newBuilder()
      .setData(ByteString.copyFromUtf8(payload))
      .build()

    logger.debug(s"[Pub/Sub] Sending event for parcel ${parcel.id}: $payload")

    // publisher.publish() is async — it returns an ApiFuture<String> (the message ID).
    // ApiFutures.addCallback fires onSuccess/onFailure when the emulator acknowledges.
    // MoreExecutors.directExecutor() runs the callback on whichever thread completes the future.
    ApiFutures.addCallback(
      publisher.publish(message),
      new ApiFutureCallback[String] {
        override def onSuccess(messageId: String): Unit =
          logger.info(s"[Pub/Sub] STATUS_CHANGED for parcel ${parcel.id} → newStatus=${parcel.currentStatus} (messageId=$messageId)")
        override def onFailure(t: Throwable): Unit =
          logger.error(s"[Pub/Sub] Failed to publish STATUS_CHANGED for parcel ${parcel.id}", t)
      },
      MoreExecutors.directExecutor()
    )
  }
}
