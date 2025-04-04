// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client

import cats.*
import cats.effect.*
import cats.effect.implicits.*
import cats.syntax.all.*
import edu.gemini.aspen.giapi.commands.Activity
import edu.gemini.aspen.giapi.commands.Command as GiapiCommand
import edu.gemini.aspen.giapi.commands.ConfigPath
import edu.gemini.aspen.giapi.commands.Configuration as GiapiConfiguration
import edu.gemini.aspen.giapi.commands.DefaultConfiguration
import edu.gemini.aspen.giapi.commands.HandlerResponse.Response
import edu.gemini.aspen.giapi.commands.SequenceCommand
import giapi.client.syntax.giapiconfig.*

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

package commands {

  sealed trait CommandCallResult {
    val response: Response
  }
  final case class CommandResult(response: Response) extends CommandCallResult
  final case class CommandResultException private (response: Response, message: String)
      extends RuntimeException(message)
      with CommandCallResult

  object CommandResultException {

    def apply(message: String): CommandResultException =
      CommandResultException(Response.ERROR, message)

    def timedOut(after: FiniteDuration): CommandResultException =
      CommandResultException(Response.ERROR, s"Timed out response after: $after")
  }

  final case class Configuration(config: Map[ConfigPath, String]) {

    def value(path: String): Option[String] =
      config.get(ConfigPath.configPath(path))

    def contains(path: String): Boolean =
      config.contains(ConfigPath.configPath(path))

    def remove(path: String): Configuration =
      Configuration(config - ConfigPath.configPath(path))

    def toGiapi: GiapiConfiguration =
      new DefaultConfiguration(new java.util.TreeMap(config.asJava))
  }

  object Configuration {
    val Zero: Configuration = Configuration(Map.empty)

    def single[A: GiapiConfig](key: String, value: A): Configuration =
      Configuration(Map(ConfigPath.configPath(key) -> value.configValue))

    implicit val eq: Eq[Configuration] = Eq.by(_.config)

    implicit val monoid: Monoid[Configuration] = new Monoid[Configuration] {
      def empty: Configuration = Zero

      def combine(a: Configuration, b: Configuration): Configuration =
        Configuration(a.config |+| b.config)
    }
  }

  final case class Command(
    sequenceCommand: SequenceCommand,
    activity:        Activity,
    config:          Configuration
  ) {

    def toGiapi: GiapiCommand =
      new GiapiCommand(sequenceCommand, activity, config.toGiapi)
  }

}

package object commands {
  val DataLabelCfg = "DATA_LABEL"

  implicit val responseEq: Eq[Response] = Eq.instance { case (a, b) =>
    a.name === b.name
  }

  implicit val scEq: Eq[SequenceCommand] = Eq.fromUniversalEquals

  /**
   * Send a command over giapi
   * @param commandsClient
   *   Client interface to send the command to the client and await the response
   * @param command
   *   The actual command sent
   * @param timeout
   *   Timeout to await a response, often 2 seconds
   * @tparam F
   *   Effect type
   * @return
   *   the result of the operation
   */
  def sendCommand[F[_]: Async](
    commandsClient: CommandSenderClient[F],
    command:        Command,
    timeOut:        FiniteDuration
  ): F[CommandCallResult] = {
    val error = CommandResultException.timedOut(timeOut)
    val e     = ApplicativeError[F, Throwable].raiseError[CommandCallResult](error)
    commandsClient.sendCommand(command).timeoutTo(timeOut, e)
  }
}
