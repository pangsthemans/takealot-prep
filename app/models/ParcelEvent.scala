package models

import java.time.Instant
import play.api.libs.json._

case class ParcelEvent(
  parcelId:   Long,
  occurredAt: Instant,
  eventType:  String,
  newStatus:  String
)

object ParcelEvent {
  private implicit val instantFormat: Format[Instant] = Format(
    Reads[Instant](_.validate[String].flatMap { s =>
      try JsSuccess(Instant.parse(s))
      catch { case _: Exception => JsError(s"Not a valid ISO-8601 instant: $s") }
    }),
    Writes[Instant](i => JsString(i.toString))
  )
  implicit val format: OFormat[ParcelEvent] = Json.format[ParcelEvent]
}
