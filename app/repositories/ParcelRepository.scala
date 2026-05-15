package repositories

import anorm._
import anorm.SqlParser._
import models.Parcel
import play.api.db.Database

import java.util.Date
import javax.inject.{Inject, Singleton}

// @Singleton: Guice creates one shared instance for the app lifetime.
// @Inject: Guice supplies the Database argument — you never call `new ParcelRepository(...)`.
@Singleton
class ParcelRepository @Inject()(db: Database) {

  private val parcelParser: RowParser[Parcel] =
    get[Long]("id") ~
    get[String]("sender_name") ~
    get[String]("recipient_name") ~
    get[String]("recipient_address") ~
    get[String]("current_status") ~
    get[Date]("created_at") ~
    get[Date]("updated_at") map {
      case id ~ sender ~ recipient ~ address ~ status ~ createdAt ~ updatedAt =>
        Parcel(Some(id), sender, recipient, address, status, createdAt.toInstant, updatedAt.toInstant)
    }

  def create(senderName: String, recipientName: String, recipientAddress: String): Parcel =
    db.withConnection { implicit conn =>
      SQL"""
        INSERT INTO parcels (sender_name, recipient_name, recipient_address)
        VALUES ($senderName, $recipientName, $recipientAddress)
        RETURNING id, sender_name, recipient_name, recipient_address, current_status, created_at, updated_at
      """.as(parcelParser.single)
    }

  def findById(id: Long): Option[Parcel] =
    db.withConnection { implicit conn =>
      SQL"SELECT * FROM parcels WHERE id = $id".as(parcelParser.singleOpt)
    }

  def updateStatus(id: Long, status: String): Option[Parcel] =
    db.withConnection { implicit conn =>
      SQL"""
        UPDATE parcels
        SET current_status = $status, updated_at = NOW()
        WHERE id = $id
        RETURNING id, sender_name, recipient_name, recipient_address, current_status, created_at, updated_at
      """.as(parcelParser.singleOpt)
    }

  def list(): List[Parcel] =
    db.withConnection { implicit conn =>
      SQL"SELECT * FROM parcels ORDER BY created_at DESC".as(parcelParser.*)
    }
}
