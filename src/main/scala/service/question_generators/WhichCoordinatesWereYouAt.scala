package service.question_generators

import akka.actor.{ActorRef, Props}
import entities.Entities.SpecificQuestionType._
import entities.Entities.{CoordinatesQuestion, Location, Question}
import reactivemongo.api.DefaultDB
import reactivemongo.bson.BSONDocument
import service.question_generators.QuestionGenerator.{CreateQuestion, FailedToCreateQuestion, FinishedQuestionCreation}

import scala.util.{Failure, Success}


object WhichCoordinatesWereYouAt {

  def props(database: DefaultDB): Props =
    Props(new WhichCoordinatesWereYouAt(database))
}

class WhichCoordinatesWereYouAt(db: DefaultDB) extends PostQuestionGenerator(db) {
  def receive = {
    case CreateQuestion(user_id) =>
      val client = sender()
      val query = BSONDocument(
        "user_id" -> user_id,
        "place" -> BSONDocument("$exists" -> "true")
      )
      getDocument(db, collection, query).onComplete {
        case Success(optPost) => optPost match {
          case Some(post) =>
            post.place match {
              case Some(place) =>
                val question = CoordinatesQuestion(post.post_id, user_id, Question("WhichCoordinateWereYouAt",
                  Some(List(post.message.getOrElse(""), post.story.getOrElse("")))),
                  Location(place.location.latitude, place.location.longitude))
                client ! FinishedQuestionCreation(question)
              case None => sendFailure(client, user_id)
            }
          case None => sendFailure(client, user_id)
        }
        case Failure(e) => sendFailure(client, user_id)
      }
  }

  def sendFailure(client: ActorRef, user_id: String): Unit = {
    client ! FailedToCreateQuestion(s"Something went wrong WhichCoordinateWereYouAt user: $user_id", GeoWhichPlaceWereYouAt)
  }

}