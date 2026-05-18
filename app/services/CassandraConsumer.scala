package services

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{AlreadyExistsException, FixedTransportChannelProvider}
import com.google.cloud.pubsub.v1.{MessageReceiver, Subscriber, SubscriptionAdminClient, SubscriptionAdminSettings}
import com.google.pubsub.v1.{ProjectSubscriptionName, PushConfig, Subscription, TopicName}
import io.grpc.ManagedChannelBuilder
import models.ParcelEvent
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import play.api.{Configuration, Logger}

import java.net.InetSocketAddress
import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Try

@Singleton
class CassandraConsumer @Inject()(
  config: Configuration,
  lifecycle: ApplicationLifecycle
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  private val host       = config.get[String]("cassandra.host")
  private val port       = config.get[Int]("cassandra.port")
  private val datacenter = config.get[String]("cassandra.datacenter")
  private val keyspace   = config.get[String]("cassandra.keyspace")

  private val projectId      = config.get[String]("pubsub.project")
  private val topicId        = config.get[String]("pubsub.topic")
  private val subscriptionId = config.get[String]("pubsub.subscription")
  private val emulatorHost   = config.get[String]("pubsub.emulatorHost")

  // @volatile: forces all reads/writes through main memory so the background Future's
  // writes are immediately visible to the thread serving HTTP requests (listEvents).
  @volatile private var session:    CqlSession        = _
  @volatile private var insertStmt: PreparedStatement = _
  @volatile private var selectStmt: PreparedStatement = _
  @volatile private var subscriber: Subscriber        = _

  // Run the full setup in a background Future so the app starts immediately
  // even while Cassandra is still booting (it takes 60-90s on first start).
  Future { initialize() }

  lifecycle.addStopHook { () =>
    Future {
      if (subscriber != null) subscriber.stopAsync()
      if (session    != null) session.close()
    }
  }

  // Retry connecting to Cassandra every 10s.
  // @annotation.tailrec: Scala compiles this as a loop rather than stacking recursive calls,
  // so 12 retries don't consume 12 stack frames. The compiler enforces this — it won't
  // compile if the recursion isn't in tail position.
  @annotation.tailrec
  private def connectWithRetry(retriesLeft: Int = 12): CqlSession =
    Try(
      CqlSession.builder()
        .addContactPoint(new InetSocketAddress(host, port))
        .withLocalDatacenter(datacenter)
        .build()
    ).toEither match {
      case Right(s) => s
      case Left(e) if retriesLeft > 0 =>
        logger.warn(s"[Cassandra] Not ready yet — retrying in 10s ($retriesLeft attempts left)")
        Thread.sleep(10_000)
        connectWithRetry(retriesLeft - 1)
      case Left(e) =>
        logger.error("[Cassandra] Could not connect after all retries", e)
        throw e
    }

  private def initialize(): Unit = {
    // ── 1. Connect ────────────────────────────────────────────────────────────
    session = connectWithRetry()
    logger.info("[Cassandra] Connected")

    // ── 2. Schema ─────────────────────────────────────────────────────────────
    // Cassandra data modelling: design your table around your query.
    // Query: "all events for parcel X, newest first"
    //   → partition key = parcel_id  (all rows for one parcel live on one node)
    //   → clustering key = occurred_at DESC  (rows within a partition sorted newest-first)
    session.execute(
      s"""CREATE KEYSPACE IF NOT EXISTS $keyspace
          WITH replication = {'class':'SimpleStrategy','replication_factor':1}"""
    )
    session.execute(
      s"""CREATE TABLE IF NOT EXISTS $keyspace.parcel_events (
            parcel_id   bigint,
            occurred_at timestamp,
            event_type  text,
            new_status  text,
            payload     text,
            PRIMARY KEY ((parcel_id), occurred_at)
          ) WITH CLUSTERING ORDER BY (occurred_at DESC)"""
    )
    logger.info(s"[Cassandra] Schema ready in keyspace '$keyspace'")

    // ── 3. Prepared statements ────────────────────────────────────────────────
    // CQL is compiled once on the Cassandra server; subsequent executions only send bound values.
    insertStmt = session.prepare(
      s"""INSERT INTO $keyspace.parcel_events
            (parcel_id, occurred_at, event_type, new_status, payload)
          VALUES (?, ?, ?, ?, ?)"""
    )
    selectStmt = session.prepare(
      s"""SELECT parcel_id, occurred_at, event_type, new_status
          FROM $keyspace.parcel_events
          WHERE parcel_id = ?"""
    )

    // ── 4. Pub/Sub emulator channel ───────────────────────────────────────────
    val channel = ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build()
    val channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
    val credProvider    = NoCredentialsProvider.create()

    // ── 5. Create subscription if it doesn't exist ────────────────────────────
    val subName   = ProjectSubscriptionName.of(projectId, subscriptionId)
    val topicName = TopicName.of(projectId, topicId)

    val adminClient = SubscriptionAdminClient.create(
      SubscriptionAdminSettings.newBuilder()
        .setTransportChannelProvider(channelProvider)
        .setCredentialsProvider(credProvider)
        .build()
    )
    try {
      adminClient.createSubscription(
        Subscription.newBuilder()
          .setName(subName.toString)
          .setTopic(topicName.toString)
          .setPushConfig(PushConfig.getDefaultInstance)
          .setAckDeadlineSeconds(30)
          .build()
      )
      logger.info(s"[Pub/Sub] Created subscription '$subscriptionId'")
    } catch {
      case _: AlreadyExistsException =>
        logger.info(s"[Pub/Sub] Subscription '$subscriptionId' already exists")
    } finally {
      adminClient.close()
    }

    // ── 6. Message handler ────────────────────────────────────────────────────
    // MessageReceiver is a Java functional interface: (PubsubMessage, AckReplyConsumer) => Unit.
    // ack() removes the message from the subscription; nack() returns it for redelivery.
    val receiver: MessageReceiver = (message, ackReply) => {
      val payload = message.getData.toStringUtf8
      Try {
        val json      = Json.parse(payload)
        val parcelId  = (json \ "parcelId").as[Long]
        val eventType = (json \ "eventType").as[String]
        val newStatus = (json \ "newStatus").as[String]
        val occurredAt = Instant.parse((json \ "occurredAt").as[String])

        session.execute(
          insertStmt.bind()
            .setLong(0, parcelId)
            .setInstant(1, occurredAt)
            .setString(2, eventType)
            .setString(3, newStatus)
            .setString(4, payload)
        )
        logger.info(s"[Cassandra] Wrote event — parcel=$parcelId newStatus=$newStatus")
        ackReply.ack()
      }.recover { case e =>
        logger.error(s"[Cassandra] Failed to process message, nacking for redelivery: ${e.getMessage}")
        ackReply.nack()
      }
    }

    // ── 7. Start subscriber ───────────────────────────────────────────────────
    // Subscriber manages an internal pull loop — no manual polling needed.
    subscriber = Subscriber.newBuilder(subName, receiver)
      .setChannelProvider(channelProvider)
      .setCredentialsProvider(credProvider)
      .build()
    subscriber.startAsync().awaitRunning()
    logger.info(s"[Pub/Sub] Consumer started — listening on subscription '$subscriptionId'")
  }

  // Called by the controller to serve GET /parcels/:id/events.
  // Returns empty list if Cassandra hasn't finished initializing yet.
  def listEvents(parcelId: Long): List[ParcelEvent] = {
    if (session == null || selectStmt == null) return List.empty
    session
      .execute(selectStmt.bind().setLong(0, parcelId))
      .iterator().asScala
      .map { row =>
        ParcelEvent(
          parcelId   = row.getLong("parcel_id"),
          occurredAt = row.getInstant("occurred_at"),
          eventType  = row.getString("event_type"),
          newStatus  = row.getString("new_status")
        )
      }
      .toList
  }
}
