// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client.gpi

import cats.effect.Resource
import cats.effect.Temporal
import cats.effect.kernel.Async
import cats.syntax.all.*
import edu.gemini.aspen.giapi.commands.Activity
import edu.gemini.aspen.giapi.commands.SequenceCommand
import fs2.Stream
import giapi.client.Giapi
import giapi.client.GiapiClient
import giapi.client.GiapiStatusDb
import giapi.client.commands.Command
import giapi.client.commands.CommandCallResult
import giapi.client.commands.Configuration
import mouse.boolean.*

import scala.concurrent.duration.*

sealed trait GpiClient[F[_]] extends GiapiClient[F] {

  def heartbeat: F[Int]

  def fpmMask: F[String]

  def aoDarkLevel: F[Float]

  def heartbeatS: F[Stream[F, Int]]

  def calExitShutter(position: Boolean): F[CommandCallResult]

  def observingMode(mode: String): F[CommandCallResult]

  def ifsConfigure(integrationTime: Double, coAdds: Int, readoutMode: Int): F[CommandCallResult]

  def alignAndCalib: F[CommandCallResult]

  def statusDb: GiapiStatusDb[F]
}

object GpiClient {
  val ALIGN_AND_CALIB_DEFAULT_MODE: Int = 4

  /**
   * Client for GPI
   */
  final private class GpiClientImpl[F[_]](
    override val giapi: Giapi[F],
    val statusDb:       GiapiStatusDb[F]
  ) extends GpiClient[F] {
    import GiapiClient.DefaultCommandTimeout
    // Align and Calib is fairly variable in duration. it can take a long time and still succeed
    // The 6 minutes timeout is based on current practice but if the system is very miss aligned
    // It could take longer and succeed but on the other hand we don't want to wait too long
    // in case of error
    val ACCommandTimeout: FiniteDuration = 6.minutes

    // /////////////
    // Status items
    // /////////////
    def heartbeat: F[Int] =
      giapi.get[Int]("gpi:heartbeat")

    def fpmMask: F[String] =
      giapi.get[String]("gpi:fpmMask")

    def aoDarkLevel: F[Float] =
      giapi.get[Float]("gpi:ao:darkLevel")

    // ///////////////////
    // Streaming statuses
    // ///////////////////
    def heartbeatS: F[Stream[F, Int]] =
      giapi.stream[Int]("gpi:heartbeat")

    // //////////////////////
    // GPI Specific commands
    // //////////////////////

    // TODO Use OCS constants for open/close
    private def shutter(shutterName: String, position: Boolean): F[CommandCallResult] =
      giapi.command(Command(
                      SequenceCommand.APPLY,
                      Activity.PRESET_START,
                      Configuration.single(s"gpi:selectShutter.$shutterName", position.fold(1, 0))
                    ),
                    DefaultCommandTimeout
      )

    def entranceShutter(position: Boolean): F[CommandCallResult] =
      shutter("entranceShutter", position)

    def calExitShutter(position: Boolean): F[CommandCallResult] =
      shutter("calExitShutter", position)

    def calEntranceShutter(position: Boolean): F[CommandCallResult] =
      shutter("calEntranceShutter", position)

    def calReferenceShutter(position: Boolean): F[CommandCallResult] =
      shutter("calReferenceShutter", position)

    def calScienceShutter(position: Boolean): F[CommandCallResult] =
      shutter("calScienceShutter", position)

    def alignAndCalib: F[CommandCallResult] =
      giapi.command(Command(
                      SequenceCommand.APPLY,
                      Activity.PRESET_START,
                      Configuration.single("gpi:alignAndCalib.part1", ALIGN_AND_CALIB_DEFAULT_MODE)
                    ),
                    ACCommandTimeout
      )

    // TODO Use OCS constants
    def observingMode(mode: String): F[CommandCallResult] =
      giapi.command(Command(SequenceCommand.APPLY,
                            Activity.PRESET_START,
                            Configuration.single("gpi:observationMode.mode", mode)
                    ),
                    DefaultCommandTimeout
      )

    def ifsFilter(filter: String): F[CommandCallResult] =
      giapi.command(Command(
                      SequenceCommand.APPLY,
                      Activity.PRESET_START,
                      Configuration.single("gpi:ifs:selectIfsFilter.maskStr", filter)
                    ),
                    DefaultCommandTimeout
      )

    def ifsConfigure(integrationTime: Double, coAdds: Int, readoutMode: Int): F[CommandCallResult] =
      giapi.command(
        Command(
          SequenceCommand.APPLY,
          Activity.PRESET_START,
          List(
            Configuration.single("gpi:configIfs.integrationTime", integrationTime),
            Configuration.single("gpi:configIfs.numCoadds", coAdds),
            Configuration.single("gpi:configIfs.readoutMode", readoutMode)
          ).combineAll
        ),
        DefaultCommandTimeout
      )
  }

  // Used for simulations
  def simulatedGpiClient[F[_]: Temporal]: Resource[F, GpiClient[F]] =
    Giapi
      .simulatedGiapiConnection[F]
      .newGiapiConnection
      .map(new GpiClientImpl[F](_, GiapiStatusDb.simulatedDb[F]))

  def gpiClient[F[_]: Async](
    name:              String,
    url:               String,
    statusesToMonitor: List[String]
  ): Resource[F, GpiClient[F]] = {
    val giapi: Resource[F, Giapi[F]] =
      Giapi.giapiConnection[F](name, url, Nil).newGiapiConnection

    val db: Resource[F, GiapiStatusDb[F]] =
      Resource.make(
        GiapiStatusDb
          .newStatusDb[F](url, statusesToMonitor, Nil)
      )(_.close)

    (giapi, db).mapN(new GpiClientImpl[F](_, _)).widen[GpiClient[F]]
  }

}

object GPIExample extends cats.effect.IOApp {

  import cats.effect.IO
  import cats.effect.ExitCode
  import scala.concurrent.duration._

  val url = "failover:(tcp://127.0.0.1:61616)"

  val gpi: Resource[IO, GpiClient[IO]] =
    GpiClient.gpiClient[IO]("gpi-example", url, Nil)

  val gpiStatus: IO[(Vector[Int], Int, String, Float)] =
    gpi.use { client =>
      for {
        hs <- client.heartbeatS.flatMap(_.take(3).compile.toVector)
        h  <- client.heartbeat
        f  <- client.fpmMask
        o  <- client.aoDarkLevel
      } yield (hs, h, f, o)
    }

  val gpiSequence: IO[CommandCallResult] =
    gpi.use { client =>
      for {
        _ <- client.calExitShutter(true)                 // Open the shutter
        _ <- client.observingMode("Y_coron")             // Change observing mode
        _ <- client.ifsConfigure(1.5, 1, 4)              // Configure the IFS
        f <- client.observe("TEST_S20180509", 5.seconds) // observe
        _ <- client.park                                 // Park at the end
      } yield f
    }

  def putLn(a: Any): IO[Unit] =
    IO.delay(println(a)) // scalastyle:off console.io

  def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- gpiStatus.flatMap(putLn)
      _ <- gpiSequence.flatMap(putLn)
    } yield ExitCode.Success

}
