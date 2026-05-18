package controllers

import models.{Parcel, ParcelEvent}
import org.mockito.Mockito.{reset, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{JsValue, Json}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import repositories.ParcelRepository
import services.{CassandraConsumer, MetricsService, ParcelEventPublisher}

import java.time.Instant

// PlaySpec = ScalaTest WordSpec + Matchers pre-configured for Play assertions.
// MockitoSugar = adds mock[T] (Mockito proxy that records calls and returns programmed values).
// BeforeAndAfterEach = runs beforeEach() before every individual "in" block.
//
// We do NOT start a Play application here. We call controller actions directly:
//   controller.create(FakeRequest().withBody(json: JsValue))
// This calls Action[JsValue].apply(Request[JsValue]), which skips body parsing
// (body is already typed) and runs the controller closure. Tests are fast, no Docker needed.
class ParcelControllerSpec extends PlaySpec with MockitoSugar with BeforeAndAfterEach {

  // mock[T] asks Mockito to generate a subclass of ParcelRepository at runtime
  // that intercepts every method call and returns whatever we program with when().
  private val repo      = mock[ParcelRepository]
  private val publisher = mock[ParcelEventPublisher]
  private val cassandra = mock[CassandraConsumer]
  private val metrics   = mock[MetricsService]

  // stubControllerComponents() is Play's test helper that provides a minimal
  // ControllerComponents (Action builder, response helpers, etc.) without a running app.
  private val controller = new ParcelController(stubControllerComponents(), repo, publisher, cassandra, metrics)

  // Reset all stubs after each test so one test's `when()` can't bleed into the next.
  override def beforeEach(): Unit = { reset(repo); reset(publisher); reset(cassandra); reset(metrics) }

  // Shared test fixture — a parcel with a fixed timestamp so JSON comparisons are stable.
  private val now    = Instant.parse("2024-01-01T00:00:00Z")
  private val sample = Parcel(Some(1L), "Alice", "Bob", "123 Main St", "PENDING", now, now)

  // Helper: build a FakeRequest with a typed JsValue body.
  // Declaring body as JsValue (not JsObject) makes withBody[JsValue] return FakeRequest[JsValue],
  // which matches Action[JsValue]'s expected request type.
  private def jsonRequest(json: JsValue): FakeRequest[JsValue] =
    FakeRequest().withBody(json)

  // ── POST /parcels ──────────────────────────────────────────────────────────

  "POST /parcels" should {

    "return 201 Created with parcel JSON for a valid request body" in {
      when(repo.create("Alice", "Bob", "123 Main St")).thenReturn(sample)

      val result = controller.create(jsonRequest(Json.obj(
        "senderName"       -> "Alice",
        "recipientName"    -> "Bob",
        "recipientAddress" -> "123 Main St"
      )))

      // status(), contentAsJson() are from Helpers._ — they await the Future[Result].
      status(result) mustBe CREATED
      (contentAsJson(result) \ "senderName").as[String]    mustBe "Alice"
      (contentAsJson(result) \ "currentStatus").as[String] mustBe "PENDING"
    }

    "return 400 Bad Request when a required field is missing" in {
      // recipientName and recipientAddress are absent — validate[CreateParcelRequest] will fail.
      val result = controller.create(jsonRequest(Json.obj("senderName" -> "Alice")))
      status(result) mustBe BAD_REQUEST
    }

    "return 400 Bad Request for a completely empty body" in {
      val result = controller.create(jsonRequest(Json.obj()))
      status(result) mustBe BAD_REQUEST
    }
  }

  // ── GET /parcels/:id ───────────────────────────────────────────────────────

  "GET /parcels/:id" should {

    "return 200 OK with the parcel JSON when the parcel exists" in {
      when(repo.findById(1L)).thenReturn(Some(sample))

      // GET actions use Action[AnyContent] — no body, so plain FakeRequest() is fine.
      // FakeRequest[AnyContentAsEmpty] <: Request[AnyContent] via Request's covariance (+A).
      val result = controller.getById(1L)(FakeRequest())

      status(result) mustBe OK
      (contentAsJson(result) \ "id").as[Long]           mustBe 1L
      (contentAsJson(result) \ "senderName").as[String] mustBe "Alice"
    }

    "return 404 Not Found when the parcel does not exist" in {
      when(repo.findById(99L)).thenReturn(None)

      val result = controller.getById(99L)(FakeRequest())

      status(result) mustBe NOT_FOUND
      (contentAsJson(result) \ "error").as[String] must include("99")
    }
  }

  // ── PATCH /parcels/:id/status ──────────────────────────────────────────────

  "PATCH /parcels/:id/status" should {

    "return 200 OK with the updated parcel when the parcel exists" in {
      val updated = sample.copy(currentStatus = "IN_TRANSIT")
      when(repo.updateStatus(1L, "IN_TRANSIT")).thenReturn(Some(updated))

      // updateStatus(id) returns Action[JsValue], then we apply the request.
      // Scala: controller.updateStatus(1L)(request) = controller.updateStatus(1L).apply(request)
      val result = controller.updateStatus(1L)(jsonRequest(Json.obj("status" -> "IN_TRANSIT")))

      status(result) mustBe OK
      (contentAsJson(result) \ "currentStatus").as[String] mustBe "IN_TRANSIT"
      // verify() asserts the mock method was called exactly once with the updated parcel.
      verify(publisher).publish(updated)
    }

    "return 404 Not Found when the parcel does not exist" in {
      when(repo.updateStatus(99L, "IN_TRANSIT")).thenReturn(None)

      val result = controller.updateStatus(99L)(jsonRequest(Json.obj("status" -> "IN_TRANSIT")))

      status(result) mustBe NOT_FOUND
    }

    "return 400 Bad Request when the status field is absent" in {
      val result = controller.updateStatus(1L)(jsonRequest(Json.obj("wrongField" -> "IN_TRANSIT")))
      status(result) mustBe BAD_REQUEST
    }
  }

  // ── GET /parcels ───────────────────────────────────────────────────────────

  "GET /parcels" should {

    "return 200 OK with a JSON array containing all parcels" in {
      when(repo.list()).thenReturn(List(sample))

      val result = controller.list()(FakeRequest())

      status(result) mustBe OK
      // .as[List[Parcel]] uses the implicit Format[Parcel] from the companion object
      contentAsJson(result).as[List[Parcel]] must have size 1
    }

    "return 200 OK with an empty array when there are no parcels" in {
      when(repo.list()).thenReturn(List.empty)

      val result = controller.list()(FakeRequest())

      status(result) mustBe OK
      contentAsJson(result).as[List[Parcel]] mustBe empty
    }
  }

  // ── GET /parcels/:id/events ────────────────────────────────────────────────

  "GET /parcels/:id/events" should {

    "return 200 OK with the event history from Cassandra" in {
      val event = ParcelEvent(1L, now, "STATUS_CHANGED", "IN_TRANSIT")
      when(cassandra.listEvents(1L)).thenReturn(List(event))

      val result = controller.events(1L)(FakeRequest())

      status(result) mustBe OK
      val json = contentAsJson(result).as[List[ParcelEvent]]
      json must have size 1
      json.head.newStatus mustBe "IN_TRANSIT"
    }

    "return 200 OK with an empty array when there are no events" in {
      when(cassandra.listEvents(1L)).thenReturn(List.empty)

      val result = controller.events(1L)(FakeRequest())

      status(result) mustBe OK
      contentAsJson(result).as[List[ParcelEvent]] mustBe empty
    }
  }
}
