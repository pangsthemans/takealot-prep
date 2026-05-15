package models

import java.time.Instant
import play.api.libs.json._

case class Parcel(
  id: Option[Long],  // None before insert, Some(id) after
  senderName: String,
  recipientName: String,
  recipientAddress: String,
  currentStatus: String,
  createdAt: Instant,
  updatedAt: Instant
)

object Parcel {
  // Play's Json.format macro doesn't know about java.time.Instant out of the box,
  // so we teach it: parse ISO-8601 strings coming in, write them going out.
  // This must be declared before `format` so the macro can find it.
  private implicit val instantFormat: Format[Instant] = Format(
    Reads[Instant](_.validate[String].flatMap { s =>
      try JsSuccess(Instant.parse(s))
      catch { case _: Exception => JsError(s"Not a valid ISO-8601 instant: $s") }
    }),
    Writes[Instant](i => JsString(i.toString))
  )

  // Json.format is a macro: it reads the case class fields and generates the OFormat at compile time.
  implicit val format: OFormat[Parcel] = Json.format[Parcel]
}

case class CreateParcelRequest(senderName: String, recipientName: String, recipientAddress: String)
object CreateParcelRequest {
  implicit val format: OFormat[CreateParcelRequest] = Json.format[CreateParcelRequest]
}

case class UpdateStatusRequest(status: String)
object UpdateStatusRequest {
  implicit val format: OFormat[UpdateStatusRequest] = Json.format[UpdateStatusRequest]
}
