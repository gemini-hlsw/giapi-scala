// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client.ghost

import cats.effect.*
import cats.effect.Temporal
import cats.syntax.all.*
import giapi.client.Giapi
import giapi.client.GiapiClient
import giapi.client.GiapiStatusDb
import giapi.client.syntax.status.*

/** Client for GHOST */
sealed trait GhostClient[F[_]] extends GiapiClient[F] {
  def guidingState: F[Option[Int]]
}

object GhostClient {
  val GuidingState = "ghost:sad:dc:ag.command_state"

  private final class GhostClientImpl[F[_]: cats.Functor](
    override val giapi: Giapi[F],
    val statusDb:       GiapiStatusDb[F]
  ) extends GhostClient[F] {
    def guidingState: F[Option[Int]] =
      statusDb.optional(GuidingState).map(_.intValue)
  }

  // Used for simulations
  def simulatedGhostClient[F[_]: Temporal]: Resource[F, GhostClient[F]] =
    Giapi
      .simulatedGiapiConnection[F]
      .newGiapiConnection
      .map(new GhostClientImpl(_, GiapiStatusDb.simulatedDb[F]))

  def ghostClient[F[_]: Async](
    name: String,
    url:  String
  ): Resource[F, GhostClient[F]] = {
    val ghostSequence: Resource[F, Giapi[F]] =
      Giapi.giapiConnection[F](s"$name-commands", url, Nil).newGiapiConnection

    val db: Resource[F, GiapiStatusDb[F]] =
      Resource.make(
        GiapiStatusDb
          .newStatusDb[F](url, List(GuidingState))
      )(_.close)

    (ghostSequence, db).mapN(new GhostClientImpl(_, _))
  }
}

object GhostExample extends IOApp {

  val url = "failover:(tcp://172.17.110.14:61616)"

  val ghostClient: Resource[IO, GhostClient[IO]] =
    GhostClient.ghostClient("ghost-example", url)

  def run(args: List[String]): IO[ExitCode] =
    ghostClient.use { client =>
      for {
        r <- client.guidingState
        _ <- IO(println(r)) // scalastyle:off console.io
      } yield ExitCode.Success
    }

}
