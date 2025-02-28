package arisia.zoom

import arisia.util.Done
import arisia.zoom.models.{ZoomMeetingType, ZoomUser, ZoomMeeting}
import play.api.libs.json._
import play.api.{Configuration, Logging}
import play.api.libs.ws._
import play.api.http.Status

import scala.concurrent.{Future, ExecutionContext}
import scala.util.Random

/**
 * Logical layer over the Zoom API.
 *
 * This doesn't try to impose any semantics -- it is just a relatively thin pass-through.
 */
trait ZoomService {
  def getUsers(): Future[List[ZoomUser]]

  def startMeeting(topic: String, zoomUserId: String): Future[Either[String, ZoomMeeting]]
  def endMeeting(meetingId: Long): Future[Done]

  def isMeetingRunning(meetingId: Long): Future[Boolean]
  def getJoinUrl(meetingId: Long): Future[String]
}

private[zoom] case class ZoomMeetingParams(
  topic: String,
  `type`: Int,
  password: String
)
object ZoomMeetingParams {
  implicit val fmt: Format[ZoomMeetingParams] = Json.format
}

class ZoomServiceImpl(
  config: Configuration,
  ws: WSClient,
  jwtService: JwtService
)(
  implicit ec: ExecutionContext
) extends ZoomService
  with Logging
{

  lazy val baseUrl: String = config.get[String]("arisia.zoom.api.baseUrl")
//  lazy val zoomUserId: String = config.get[String]("arisia.zoom.api.userId")

  def urlWithJwt(url: String): WSRequest = {
    val jwt = jwtService.getJwtToken()
    ws.url(s"$baseUrl$url")
      .addHttpHeaders(
        ("Authorization", jwt)
      )
  }

  def getUsers(): Future[List[ZoomUser]] = {
    urlWithJwt("/users")
      .get()
      .map { response =>
        // TODO: remove the log, and make the response real.
        logger.info(s"The response from Zoom is $response, body ${response.body}")
        List.empty
      }
  }

  def startMeeting(topic: String, zoomUserId: String): Future[Either[String, ZoomMeeting]] = {
    logger.info(s"Starting meeting $topic")
    // Generate a random password for this meeting:
    val password = Random.alphanumeric.take(10).mkString
    val params = ZoomMeetingParams(
      topic,
      ZoomMeetingType.Instant,
      password
    )
    urlWithJwt(s"/users/$zoomUserId/meetings")
      .post(Json.toJson(params))
      .map { response =>
        if (response.status == Status.CREATED) {
          logger.info(s"Meeting $topic started")
          Right(Json.parse(response.body).as[ZoomMeeting])
        } else {
          val error = s"Failure in trying to start a meeting: ${response.status}"
          logger.error(error)
          Left(error)
        }
      }
  }

  def endMeeting(meetingId: Long): Future[Done] = {
    urlWithJwt(s"/meetings/$meetingId/status")
      // This one is so simple, we're not even bothering to make a data structure yet:
      .put(JsObject(Seq(("action", JsString("end")))))
      .map { response =>
        if (response.status != Status.NO_CONTENT) {
          logger.error(s"Failure when trying to stop meeting $meetingId: ${response.status}:\n${response.body}")
        }
        Done
      }
  }

  def isMeetingRunning(meetingId: Long): Future[Boolean] = {
    urlWithJwt(s"/meetings/$meetingId")
      .get()
      .map { response =>
        val json = Json.parse(response.body)
        val status = (json \ "status").as[String]
        (status == "started")
      }
  }

  def getJoinUrl(meetingId: Long): Future[String] = {
    urlWithJwt(s"/meetings/$meetingId")
      .get()
      .map { response =>
        val json = Json.parse(response.body)
        val result = (json \ "join_url").as[String]
        result
      }
  }
}

class DisabledZoomService() extends ZoomService {
  def getUsers(): Future[List[ZoomUser]] = ???
  def startMeeting(topic: String, zoomUserId: String): Future[Either[String, ZoomMeeting]] = ???
  def endMeeting(meetingId: Long): Future[Done] = ???
  def isMeetingRunning(meetingId: Long): Future[Boolean] = ???
  def getJoinUrl(meetingId: Long): Future[String] = ???
}
