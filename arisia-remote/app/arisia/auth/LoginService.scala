package arisia.auth

import arisia.db.DBService
import arisia.discord.DiscordMember

import scala.concurrent.duration._
import arisia.models.{LoginUser, LoginId, BadgeNumber, Permissions, LoginName}
import arisia.util.Done
import cats.data.{EitherT, OptionT}
import cats.implicits._
import doobie.free.connection.ConnectionIO
import play.api.libs.ws.WSClient
import doobie._
import doobie.implicits._
import play.api.Configuration

import scala.concurrent.{Future, ExecutionContext}

trait LoginService {
  /**
   * Given credentials, says whether they match a known login.
   */
  def login(id: String, password: String): Future[Either[LoginError, LoginUser]]

  /**
   * Fetch any additional permissions that this person might have.
   */
  def getPermissions(id: LoginId): Future[Permissions]

  /**
   * Adds the given Discord credentials to the specified user.
   */
  def addDiscordInfo(who: LoginUser, discordMember: DiscordMember): Future[Int]

  /**
   * Get the user from their badge number.
   */
  def fetchUserInfo(badgeNumber: BadgeNumber): Future[Option[LoginUser]]

  /**
   * Get the user from their Discord ID.
   */
  def fetchUserFromDiscordId(memberId: String): Future[Option[LoginUser]]
}

class LoginServiceImpl(
  ws: WSClient,
  dbService: DBService,
  config: Configuration,
  cmService: CMService
)(
  implicit ec: ExecutionContext
) extends LoginService {

  /**
   * These two config settings are intended for local development use only -- to allow yourself to frontend access
   * or admin access, add your CM username to these fields in your secrets.conf:
   */
  lazy val hardcodedEarlyAccess: Seq[LoginId] =
    config.get[Seq[String]]("arisia.allow.logins").map(LoginId(_))

  lazy val hardcodedAdmin: Seq[LoginId] =
    config.get[Seq[String]]("arisia.dev.admins").map(LoginId(_))

  private def checkPermissions(initialUser: LoginUser): Future[Either[LoginError, LoginUser]] = {
    getPermissions(initialUser.id).map { perms =>
      if (perms.tech)
        Right(initialUser.copy(zoomHost = true))
      else
        Right(initialUser)
    }
  }

  def login(idFromUser: String, password: String): Future[Either[LoginError, LoginUser]] = {
    // Normalize everything to lowercase:
    val id = idFromUser.toLowerCase()
    val result: EitherT[Future, LoginError, LoginUser] = for {
      login <- EitherT(cmService.checkLogin(id, password))
      (id, badgeName) = login
      details <- EitherT(cmService.fetchDetails(id))
      // Bleah -- this EitherT math isn't nearly as easy as it should be. This is crying out for a higher-level
      // function of some sort:
      _ <-
        if (details.active) {
          EitherT.rightT[Future, LoginError](details)
        } else {
          EitherT.leftT[Future, LoginUser](LoginError.NoMembership)
        }
      _ <-
        if (details.membershipType == MembershipType.NoMembership)
          EitherT.leftT[Future, LoginUser](LoginError.NoMembership)
        else
          EitherT.rightT[Future, LoginError](details)
      _ <-
        if (details.signedCoC)
          EitherT.rightT[Future, LoginError](details)
        else
          EitherT.leftT[Future, LoginUser](LoginError.NoCoC)
      initialUser = LoginUser(id, badgeName, details.badgeNumber, false, details.membershipType)
      _ <- EitherT(recordUserInfo(initialUser))
      withPermissions <- EitherT(checkPermissions(initialUser))
    }
      yield withPermissions

    result.value
  }

  def recordUserInfo(user: LoginUser): Future[Either[LoginError, Done]] = {
    dbService.run(
      sql"""
            INSERT INTO user_info
            (username, badge_number, badge_name, membership_type)
            VALUES
            (${user.id.lower}, ${user.badgeNumber.v}, ${user.name.v}, ${user.membershipType.value})
            ON CONFLICT DO NOTHING
           """
        .update
        .run
    ).map { _ =>
      Right(Done)
    }
  }

  def fetchUserInfo(badgeNumber: BadgeNumber): Future[Option[LoginUser]] = {
    dbService.run(
      sql"""
           SELECT username, badge_name, badge_number, membership_type
             FROM user_info
            WHERE badge_number = ${badgeNumber.v}"""
        .query[(String, String, String, String)]
        .option
    ).map {
      _.map { case (username, badgeName, badgeNumber, membershipType) =>
          LoginUser(
            LoginId(username),
            LoginName(badgeName),
            BadgeNumber(badgeNumber),
            false,
            MembershipType.withValue(membershipType)
          )
      }
    }
  }

  def addDiscordInfo(who: LoginUser, discordMember: DiscordMember): Future[Int] = {
    dbService.run(
      sql"""
            UPDATE user_info
               SET discord_username = ${discordMember.user.username},
                   discord_discriminator = ${discordMember.user.discriminator},
                   discord_id = ${discordMember.user.id}
             WHERE username = ${who.id.lower}
        """
        .update
        .run
    )
  }

  def fetchUserFromDiscordId(memberId: String): Future[Option[LoginUser]] = {
    dbService.run(
      sql"""
           SELECT username, badge_name, badge_number, membership_type
             FROM user_info
            WHERE discord_id = $memberId"""
        .query[(String, String, String, String)]
        .option
    ).map {
      _.map { case (username, badgeName, badgeNumber, membershipType) =>
        LoginUser(
          LoginId(username),
          LoginName(badgeName),
          BadgeNumber(badgeNumber),
          false,
          MembershipType.withValue(membershipType)
        )
      }
    }
  }

  def fetchPermissionsQuery(id: LoginId): ConnectionIO[Option[Permissions]] =
    sql"""SELECT super_admin, admin, early_access, tech
         |FROM permissions
         |WHERE username = ${id.lower}""".stripMargin
    .query[Permissions]
    .option

  def getPermissions(id: LoginId): Future[Permissions] = {
    dbService.run(fetchPermissionsQuery(id)).map { dbPerms =>
      // If we didn't find an entry in the database for this LoginUser, then they have empty Permissions:
      val perms = dbPerms.getOrElse(Permissions.empty)
      val withHardcodedEarlyAccess =
        if (hardcodedEarlyAccess.contains(id)) {
          perms.copy(earlyAccess = true)
        } else {
          perms
        }
      val withHardcodedAdmin =
        if (hardcodedAdmin.contains(id)) {
          withHardcodedEarlyAccess.copy(admin = true)
        } else {
          withHardcodedEarlyAccess
        }
      withHardcodedAdmin
    }
  }
}
