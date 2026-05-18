package controllers

import models.{CreateParcelRequest, UpdateStatusRequest}
import play.api.libs.json._
import play.api.mvc._
import repositories.ParcelRepository
import services.ParcelEventPublisher

import javax.inject.{Inject, Singleton}

@Singleton
class ParcelController @Inject()(
  val controllerComponents: ControllerComponents,
  parcelRepo: ParcelRepository,
  eventPublisher: ParcelEventPublisher
) extends BaseController {

  def create: Action[JsValue] = Action(parse.json) { request =>
    request.body.validate[CreateParcelRequest] match {
      case JsSuccess(req, _) =>
        val parcel = parcelRepo.create(req.senderName, req.recipientName, req.recipientAddress)
        Created(Json.toJson(parcel))
      case JsError(_) =>
        BadRequest(Json.obj("error" -> "Invalid request body"))
    }
  }

  def getById(id: Long): Action[AnyContent] = Action {
    parcelRepo.findById(id) match {
      case Some(parcel) => Ok(Json.toJson(parcel))
      case None         => NotFound(Json.obj("error" -> s"Parcel $id not found"))
    }
  }

  def updateStatus(id: Long): Action[JsValue] = Action(parse.json) { request =>
    request.body.validate[UpdateStatusRequest] match {
      case JsSuccess(req, _) =>
        parcelRepo.updateStatus(id, req.status) match {
          case Some(parcel) =>
            eventPublisher.publish(parcel)
            Ok(Json.toJson(parcel))
          case None => NotFound(Json.obj("error" -> s"Parcel $id not found"))
        }
      case JsError(_) =>
        BadRequest(Json.obj("error" -> "Invalid request body"))
    }
  }

  def list: Action[AnyContent] = Action {
    Ok(Json.toJson(parcelRepo.list()))
  }
}
// nur