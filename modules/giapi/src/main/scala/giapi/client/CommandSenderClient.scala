// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client

import cats.effect.Async
import cats.effect.Resource
import cats.effect.Sync
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import edu.gemini.aspen.giapi.commands.HandlerResponse
import edu.gemini.aspen.giapi.commands.HandlerResponse.Response
import edu.gemini.aspen.giapi.util.jms.JmsKeys
import edu.gemini.aspen.giapi.util.jms.MessageBuilderFactory
import edu.gemini.jms.activemq.provider.ActiveMQJmsProvider
import giapi.client.commands.Command
import giapi.client.commands.CommandCallResult
import giapi.client.commands.CommandResult
import giapi.client.commands.CommandResultException

import javax.jms.Connection
import javax.jms.DeliveryMode
import javax.jms.MapMessage
import javax.jms.Message
import javax.jms.MessageListener
import javax.jms.MessageProducer
import javax.jms.Queue
import javax.jms.Session

trait CommandSenderClient[F[_]] {

  /**
   * Sends a command to the GMP. The listener will be called along the process, however the the
   * result of the process is returned as the final value
   *
   * Note this method does not time out in case the remote accepts the command but fails in between
   * the caller is responsible of defining a timeout
   *
   * @param command
   * @param listener
   * @return
   */
  def sendCommand(command: Command): F[CommandCallResult]

}

object CommandSenderClient {
  private val CommandsDestination = JmsKeys.GW_COMMAND_TOPIC
  private val ReplyDestination    = JmsKeys.GW_COMMAND_REPLY_QUEUE

  private case class CommandSenderClientImpl[F[_]: Async](
    connection: Connection,
    session:    Session,
    producer:   MessageProducer,
    dest:       Queue
  ) extends CommandSenderClient[F] {

    def close: F[Unit] =
      // Safely close the connection
      for {
        _ <- Sync[F].delay(producer.close()).attempt
        _ <- Sync[F].delay(session.close()).attempt
        _ <- Sync[F].delay(connection.close()).attempt
      } yield ()

    private def stringValue(response: MapMessage, key: String): Option[String] =
      // There is some inconsistency on what the GMP sends over JMS, this handles either case
      Option(response.getStringProperty(key)).orElse(Option(response.getString(key)))

    // Parser for the values sent over JMS
    // There is code on the java code to do this but there are some edge cases that are not handled
    private def parseResponse(response: Message): Option[CommandCallResult] = response match {
      case a: MapMessage =>
        val response =
          stringValue(a, JmsKeys.GMP_HANDLER_RESPONSE_KEY).map(HandlerResponse.Response.valueOf)
        val error    = stringValue(a, JmsKeys.GMP_HANDLER_RESPONSE_ERROR_KEY)
        (response, error) match {
          case (Some(_), Some(e)) => CommandResultException(e).some
          case (Some(r), None)    => CommandResult(r).some
          case _                  => none
        }
      case _             => none
    }

    /**
     * Sending the command involves sending a command to the GMP over JMS and wait for the response.
     * The response is parsed and the result is returned to the caller depending on the content. The
     * logic follows the giapi speec:
     *
     *   - If the response is ACCEPTED, the command is accepted and the result is returned
     *   - If the response is STARTED, we still wait for a completed to to be sent. At this level
     *     we'd wait forever. It is recommend to add an external timeout
     *   - If the response is COMPLETED, the command was completed successfully and returned to the
     *     cliient
     *   - If the response is NOANSWER, the command was not answered by the instrument, maybe it is
     *     not connected or dead. There is a timeout for this defined on the GMP side
     *   - If the response is ERROR, something failed on the instrument and we return it upstream
     *   - There is also the option that we cannot parse the reply in that case we produce an error
     *
     * Most of the calls are to old java code wrapped on F to make it async and easy to call by
     * clients
     */
    override def sendCommand(command: Command): F[CommandCallResult] =
      Async[F].async(cb =>
        for {
          id    <- UUIDGen.randomUUID.map(_.toString)
          reply <-
            Sync[F]
              .delay {
                // Await for reply on this consumer
                val responseConsumer  = session.createConsumer(dest)
                // We need to close the consumer after we get a response or we will leak
                // resources and get meessages for every command
                def safeClose(): Unit =
                  try
                    responseConsumer.close()
                  catch {
                    case _: Exception =>
                  }

                // Listen to a JMS message, parse it and return the result to the caller
                responseConsumer.setMessageListener(new MessageListener {
                  override def onMessage(response: Message): Unit =
                    if (response.getJMSCorrelationID === id) {
                      parseResponse(response) match {
                        case Some(r @ CommandResult(Response.ACCEPTED))  =>
                          safeClose()
                          cb(Right(r))
                        case Some(CommandResult(Response.STARTED))       =>
                        // We have to wait, do nothing
                        case Some(r @ CommandResult(Response.COMPLETED)) =>
                          safeClose()
                          cb(Right(r))
                        // Cannot happen, but required by the compiler
                        case Some(CommandResult(Response.ERROR))         =>
                          safeClose()
                        case Some(CommandResult(Response.NOANSWER))      =>
                          safeClose()
                          cb(
                            Left(
                              CommandResultException("No answer from the instrument")
                            )
                          )
                        case Some(CommandResultException(_, msg))        =>
                          safeClose()
                          cb(Left(new GiapiException(msg)))
                        case None                                        =>
                          safeClose()
                          cb(
                            Left(
                              new GiapiException("Incorrect response, possible coding error")
                            )
                          )
                      }
                    }
                })
                responseConsumer
              }
          _     <- Sync[F]
                     .delay {
                       // Send the message to the GMP
                       val messageBuilder =
                         MessageBuilderFactory.newMessageBuilder(command.toGiapi, id)
                       val msg            = session.createMapMessage()
                       messageBuilder.constructMessageBody(msg)
                       msg.setJMSReplyTo(dest)
                       producer.send(msg)
                     }
                     .handleError { e =>
                       cb(Left(e))
                     }
        } yield Some(Sync[F].delay(reply.close()).attempt.void)
      )

  }

  private def setupConnection[F[_]: Async](
    name: String,
    c:    ActiveMQJmsProvider
  ): F[CommandSenderClientImpl[F]] = Sync[F].delay {
    val factory    = c.getConnectionFactory()
    val connection = factory.createConnection()
    connection.setClientID(name)
    connection.start()

    val session  = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    val producer = session.createProducer(session.createTopic(CommandsDestination))
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT)

    val dest = session.createQueue(ReplyDestination)
    CommandSenderClientImpl[F](connection, session, producer, dest)
  }

  def commandSenderClient[F[_]: Async](
    name: String,
    c:    ActiveMQJmsProvider
  ): Resource[F, CommandSenderClient[F]] =
    Resource.make(setupConnection(name, c))(_.close)
}
