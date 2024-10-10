// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client

import cats.effect.IO
import cats.effect.Resource
import edu.gemini.aspen.giapi.commands.Activity
import edu.gemini.aspen.giapi.commands.Command as JCommand
import edu.gemini.aspen.giapi.commands.CommandSender
import edu.gemini.aspen.giapi.commands.CompletionListener
import edu.gemini.aspen.giapi.commands.HandlerResponse
import edu.gemini.aspen.giapi.commands.SequenceCommand
import edu.gemini.aspen.gmp.commands.jms.clientbridge.CommandMessagesBridgeImpl
import edu.gemini.aspen.gmp.commands.jms.clientbridge.CommandMessagesConsumer
import edu.gemini.jms.activemq.provider.ActiveMQJmsProvider
import giapi.client.commands.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

final case class GmpCommands(amq: ActiveMQJmsProvider, cmc: CommandMessagesConsumer)

object GmpCommands {

  def amqUrl(name: String): String =
    s"vm://$name?broker.useJmx=false&marshal=false&broker.persistent=false"

  def amqUrlConnect(name: String): String =
    s"vm://$name?marshal=false&broker.persistent=false&create=false"

  /**
   * Setup a mini gmp that can store and provide status items
   */
  def createGmpCommands(amqUrl: String, handleCommands: Boolean): IO[GmpCommands] = IO {
    // Local in memory broker
    val amq                     = new ActiveMQJmsProvider(amqUrl)
    amq.startConnection()
    val cs                      = new CommandSender() {
      // This is essentially an instrument simulator
      // we test several possible scenarios
      override def sendCommand(command: JCommand, listener: CompletionListener): HandlerResponse =
        sendCommand(command, listener, 0)

      override def sendCommand(
        command:  JCommand,
        listener: CompletionListener,
        timeout:  Long
      ): HandlerResponse =
        command.getSequenceCommand match {
          case SequenceCommand.INIT => HandlerResponse.COMPLETED
          case SequenceCommand.PARK => HandlerResponse.STARTED
          case _                    => HandlerResponse.NOANSWER
        }

    }
    val cmb                     = new CommandMessagesBridgeImpl(amq, cs)
    val commandMessagesConsumer = new CommandMessagesConsumer(cmb)
    if (handleCommands) {
      commandMessagesConsumer.startJms(amq)
    }

    GmpCommands(amq, commandMessagesConsumer)
  }

  def closeGmpCommands(gmp: GmpCommands): IO[Unit] = IO.apply {
    gmp.cmc.stopJms()
    gmp.amq.stopConnection()
  }
}

/**
 * Tests of the giapi api
 */
final class GiapiCommandSpec extends CatsEffectSuite {

  def client(amqUrl: String, handleCommands: Boolean): Resource[IO, Giapi[IO]] =
    for {
      _ <- Resource.make(GmpCommands.createGmpCommands(amqUrl, handleCommands))(
             GmpCommands.closeGmpCommands
           )
      c <- Giapi.giapiConnection[IO]("test", amqUrl, Nil).newGiapiConnection
    } yield c

  test("Test sending a command with no handlers") {
    client(GmpCommands.amqUrl("test1"), handleCommands = false)
      .use { c =>
        c.command(Command(SequenceCommand.TEST, Activity.PRESET, Configuration.Zero), 1.second)
          .attempt
      }
      .map(
        assertEquals(_, Left(CommandResultException("Timed out response after: 1 second")))
      )
  }

  test("Test sending a command with no answer") {
    client(GmpCommands.amqUrl("test2"), handleCommands = true)
      .use { c =>
        c.command(Command(SequenceCommand.TEST, Activity.PRESET, Configuration.Zero), 1.second)
          .attempt
      }
      .map(
        assertEquals(
          _,
          Left(CommandResultException("No answer from the instrument"))
        )
      )
  }

  test("Test sending a command with immediate answer") {
    client(GmpCommands.amqUrl("test3"), handleCommands = true)
      .use { c =>
        c.command(Command(SequenceCommand.INIT, Activity.PRESET, Configuration.Zero), 1.second)
          .attempt
      }
      .map(assertEquals(_, Right(CommandResult(HandlerResponse.Response.COMPLETED))))
  }

  test("Test sending a command with accepted but never completed answer") {
    val timeout = 1.second
    client(GmpCommands.amqUrl("test4"), handleCommands = true)
      .use { c =>
        c.command(Command(SequenceCommand.PARK, Activity.PRESET, Configuration.Zero), timeout)
          .attempt
      }
      .map(assertEquals(_, Left(CommandResultException.timedOut(timeout))))
  }

}
