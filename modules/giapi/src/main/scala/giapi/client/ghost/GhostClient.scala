// Copyright (c) 2016-2019 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client.ghost

import cats._
import cats.effect._
import cats.implicits._
import giapi.client.Giapi
import giapi.client.GiapiClient
import scala.concurrent.duration._

/** Client for GHOST */
sealed trait GhostClient[F[_]] extends GiapiClient[F]

object GhostClient {
  private final class GhostClientImpl[F[_]](override val giapi: Giapi[F])
    extends GhostClient[F]

  // Used for simulations
  def simulatedGhostClient[F[_]: Timer: ApplicativeError[?[_], Throwable]]: Resource[F, GhostClient[F]] =
    Resource.liftF(
      Giapi.simulatedGiapiConnection[F].connect.map(new GhostClientImpl(_))
    )

  def ghostClient[F[_]: Timer: ConcurrentEffect](
    url:     String
  ): Resource[F, GhostClient[F]] = {
    val ghostStatus: Resource[F, Giapi[F]] =
      Resource.make(
        Giapi.giapiConnection[F](url).connect)(_.close)

    val ghostSequence: Resource[F, Giapi[F]] =
      Resource.make(
        Giapi.giapiConnection[F](url).connect)(_.close)

    for {
      _ <- ghostStatus
      c <- ghostSequence
    } yield new GhostClientImpl(c)
  }
}

object GhostExample extends IOApp {

  val url = "failover:(tcp://127.0.0.1:61616)"

  val ghostClient: Resource[IO, GhostClient[IO]] =
    GhostClient.ghostClient(url)

  def run(args: List[String]): IO[ExitCode] =
    ghostClient.use { client =>
      for {
        r <- client.observe("TEST_S20180509", 5.seconds)
        _ <- IO(println(r)) // scalastyle:off console.io
      } yield ExitCode.Success
    }

}
