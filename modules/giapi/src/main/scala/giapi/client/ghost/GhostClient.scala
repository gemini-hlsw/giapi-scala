// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client.ghost

import cats.effect.*
import cats.effect.Temporal
import giapi.client.Giapi
import giapi.client.GiapiClient

import scala.concurrent.duration.*

/** Client for GHOST */
sealed trait GhostClient[F[_]] extends GiapiClient[F]

object GhostClient {
  private final class GhostClientImpl[F[_]](override val giapi: Giapi[F]) extends GhostClient[F]

  // Used for simulations
  def simulatedGhostClient[F[_]: Temporal]: Resource[F, GhostClient[F]] =
    Giapi.simulatedGiapiConnection[F].newGiapiConnection.map(new GhostClientImpl(_))

  def ghostClient[F[_]: Async](
    name: String,
    url:  String
  ): Resource[F, GhostClient[F]] = {
    val ghostSequence: Resource[F, Giapi[F]] =
      Giapi.giapiConnection[F](s"$name-commands", url, Nil).newGiapiConnection

    ghostSequence.map(new GhostClientImpl(_))
  }
}

object GhostExample extends IOApp {

  val url = "failover:(tcp://127.0.0.1:61616)"

  val ghostClient: Resource[IO, GhostClient[IO]] =
    GhostClient.ghostClient("ghost-example", url)

  def run(args: List[String]): IO[ExitCode] =
    ghostClient.use { client =>
      for {
        r <- client.observe("TEST_S20180509", 5.seconds)
        _ <- IO(println(r)) // scalastyle:off console.io
      } yield ExitCode.Success
    }

}
