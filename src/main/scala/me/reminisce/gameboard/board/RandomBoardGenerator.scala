package me.reminisce.gameboard.board

import akka.actor.ActorRef
import me.reminisce.database.MongoDatabaseService
import me.reminisce.database.StatsEntities.{ItemStats, UserStats}
import me.reminisce.gameboard.board.BoardGenerator.{FailedBoardGeneration, FinishedBoardGeneration, createBuckets}
import me.reminisce.gameboard.board.GameboardEntities.QuestionKind._
import me.reminisce.gameboard.board.GameboardEntities.Tile
import me.reminisce.gameboard.board.TileGenerator.{CreateTile, FailedTileCreation, FinishedTileCreation}
import me.reminisce.gameboard.questions.QuestionGenerationConfig
import me.reminisce.stats.StatsDataTypes._
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.BSONDocument

import scala.util.Random

object RandomBoardGenerator

/**
  * Abstract implmentation of a random board generator
  *
  * @param database database in which the data is stored
  * @param userId   user for which the board is generated
  * @param strategy strategy used to generate this board, depends on the actual random used
  */
abstract class RandomBoardGenerator(database: DefaultDB, userId: String, strategy: String) extends BoardGenerator(database, userId) {

  val orderingItemsNumber = QuestionGenerationConfig.orderingItemsNumber

  /**
    * Awaits feedback from workers. Handles the following messages:
    * - FinishedTileCreation(usrId, tile): a tile was created, add it to tiles and check if all the tiles are done
    * - FailedTIleCreation(message): failed to generate a tile, report to client
    *
    * @param client original requester
    * @param tiles  already generated tiles
    * @return Nothing
    */
  protected def awaitFeedBack(client: ActorRef, tiles: List[Tile]): Receive = {
    case FinishedTileCreation(usrId, tile) =>
      val newTiles = tile :: tiles
      if (newTiles.length == 9) {
        val shuffledTiles = Random.shuffle(newTiles)
        client ! FinishedBoardGeneration(shuffledTiles, strategy)
      } else {
        context.become(awaitFeedBack(client, newTiles))
      }
    case FailedTileCreation(message) =>
      log.error(s"Failed board creation: $message")
      client ! FailedBoardGeneration(message)
    case any =>
      log.error(s"${this.getClass.getName} received unknown message : $any")
  }


  /**
    * Board generation logic based on the user stats. As this is a random generation, we use two random drawers,
    * one to decide the question kinds and one to decide the data types associated with the kinds. The actual drawers
    * can be fully random or biased.
    *
    * @param userStats        generated statistics about the user
    * @param client           original requester
    * @param randomKindDrawer random drawer for question kinds
    * @param randomTypeDrawer random drawer for data types
    */
  protected def generateBoard(userStats: UserStats, client: ActorRef)
                             (randomKindDrawer: (List[Int], List[QuestionKind], Int, Int) => List[QuestionKind],
                              randomTypeDrawer: (List[Int], List[DataType], Int, Int) => List[DataType]): Unit = {
    // An order question is made of multiple items
    val normalizedCounts = userStats.questionCounts.map {
      case (k, v) =>
        if (k == Order.toString) {
          k -> v / orderingItemsNumber
        } else {
          k -> v
        }
    }

    val totalCount = normalizedCounts.toList.map { case (k, v) => v }.sum

    if (totalCount >= 27) {
      val tlCount = normalizedCounts.getOrElse(Timeline.toString, 0)
      val ordCount = normalizedCounts.getOrElse(Order.toString, 0)
      val mcCount = normalizedCounts.getOrElse(MultipleChoice.toString, 0)
      val geoCount = normalizedCounts.getOrElse(Geolocation.toString, 0)
      val selectedKinds = randomKindDrawer(List(tlCount, ordCount, mcCount, geoCount),
        List(Timeline, Order, MultipleChoice, Geolocation), 27, 1)

      val pairsKindType: List[(QuestionKind, DataType)] = selectedKinds.groupBy(el => el).toList.flatMap {
        case (kind, kindList) =>
          val possTypes = possibleTypes(kind)
          val counts = possTypes.map(t => userStats.dataTypeCounts.getOrElse(t.name, 0))
          val selectedTypes =
            if (kind == Order) {
              randomTypeDrawer(counts, possTypes, kindList.length, orderingItemsNumber)
            } else {
              randomTypeDrawer(counts, possTypes, kindList.length, 1)
            }
          kindList.zip(selectedTypes)
      }

      generateWithKindTypePairs(List(), pairsKindType, client)

    } else {
      client ! FailedBoardGeneration(s"Failed to generate board for user $userId : not enough data.")
    }

  }

  /**
    * Generates a list of (QuestionKind, DataType, List[(String, String)]) tuples which define questions. See
    * [[me.reminisce.gameboard.board.BoardGenerator.generateTiles]] for an explanation on the format.
    *
    * @param alreadyGenerated already generated questions
    * @param pairsKindType    remaining (QuestionKind, DataType) pairs
    * @param client           original requester
    */
  protected def generateWithKindTypePairs(alreadyGenerated: List[(QuestionKind, DataType, List[(String, String)])],
                                          pairsKindType: List[(QuestionKind, DataType)], client: ActorRef): Unit = pairsKindType match {
    case (cKind, cType) :: tail =>
      //Partitions into two : the ones matching the same kind/type as the head and the rest
      pairsKindType.partition { case (kind, dType) => (kind == cKind) && (dType == cType) } match {
        case (current, rest) =>
          val itemsStatsCollection = database[BSONCollection](MongoDatabaseService.itemsStatsCollection)
          val query = BSONDocument("userId" -> userId, "dataTypes" -> BSONDocument("$in" -> List(cType.name)), "readForStats" -> true)
          findSomeRandom[ItemStats](itemsStatsCollection, query, client) {
            listItemsStats =>
              cKind match {
                case Order =>
                  if (listItemsStats.length >= orderingItemsNumber * current.length) {
                    // groups the retrieved stats by item type
                    val groups = listItemsStats.groupBy(is => is.itemType).toList.map {
                      case (itemType, list) => list.map(itemStats => (itemStats.itemId, itemStats.itemType))
                    }
                    // generate buckets of items to order
                    val randomBuckets = Random.shuffle(groups.flatMap(list => createBuckets[(String, String)](list, orderingItemsNumber)))
                    if (randomBuckets.length >= current.length) {
                      // associate the (kind, type) tuples with a bucket
                      val newFound = current.zip(randomBuckets).map {
                        case ((kind, dType), v) => (kind, dType, v)
                      }
                      generateWithKindTypePairs(alreadyGenerated ++ newFound, rest, client)
                    } else {
                      client ! FailedBoardGeneration(s"Failed to generate board for user $userId : not enough data.")
                    }
                  } else {
                    client ! FailedBoardGeneration(s"Failed to generate board for user $userId : not enough data.")
                  }
                case _ =>
                  if (listItemsStats.length >= current.length) {
                    //associate the (kind, type) tuples to values
                    val newFound = current.zip(listItemsStats.map(is => (is.itemId, is.itemType))).map {
                      case ((kind, dType), v) => (kind, dType, List(v))
                    }
                    generateWithKindTypePairs(alreadyGenerated ++ newFound, rest, client)
                  } else {
                    client ! FailedBoardGeneration(s"Failed to generate board for user $userId : not enough data.")
                  }
              }
          }
      }
    case _ =>
      sendOrders(client, alreadyGenerated)

  }

  /**
    * Based on the generating questions, generates CreateTile orders for each tile (see
    * [[me.reminisce.gameboard.board.TileGenerator]]) and sends them to workers
    *
    * @param client original requester
    * @param orders questions generated by
    *               [[me.reminisce.gameboard.board.RandomBoardGenerator.generateWithKindTypePairs]]
    */
  protected def sendOrders(client: ActorRef, orders: List[(QuestionKind, DataType, List[(String, String)])]): Unit = {
    val tiles = generateTiles(orders, client)
    if (tiles.length != 9) {
      client ! FailedBoardGeneration(s"Number of tiles in invalid : ${tiles.length}.")
    } else {
      context.become(awaitFeedBack(client, List()))
      tiles.foreach {
        case (kind, choices) =>
          val worker = context.actorOf(TileGenerator.props(database))
          val req = CreateTile(userId, choices, kind)
          worker ! req
      }
    }
  }

}
