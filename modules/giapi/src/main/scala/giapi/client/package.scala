// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi

import cats.*
import cats.effect.*
import cats.effect.Temporal
import cats.effect.std.Queue
import cats.syntax.all.*
import edu.gemini.aspen.giapi.commands.HandlerResponse.Response
import edu.gemini.aspen.giapi.commands.SequenceCommand
import edu.gemini.aspen.giapi.status.StatusHandler
import edu.gemini.aspen.giapi.status.StatusItem
import edu.gemini.aspen.giapi.statusservice.StatusHandlerAggregate
import edu.gemini.aspen.giapi.statusservice.StatusService
import edu.gemini.aspen.giapi.util.jms.status.StatusGetter
import edu.gemini.jms.activemq.provider.ActiveMQJmsProvider
import fs2.Stream
import giapi.client.commands.*
import giapi.client.commands.Command

import scala.concurrent.duration.*
import scala.reflect.ClassTag

package client {

  import cats.effect.std.Dispatcher
  import giapi.client.commands.CommandResult

  final case class GiapiException(str: String) extends RuntimeException(str)

  /**
   * Typeclass to present as evidence when calling `Giapi.get`
   */
  sealed abstract class ItemGetter[A](implicit ct: ClassTag[A]) {

    /**
     * Attempt to convert any value to A as sent by StatusHandler
     */
    def value(p: Any): Option[A] = ct.unapply(p)
  }

  object ItemGetter {

    @inline
    def apply[F](implicit instance: ItemGetter[F]): ItemGetter[F] = instance

    /**
     * Allowed types according to GIAPI
     */
    implicit val strItemGetter: ItemGetter[String] = new ItemGetter[String] {}

    implicit val doubleItemGetter: ItemGetter[Double] = new ItemGetter[Double] {}

    implicit val intItemGetter: ItemGetter[Int] = new ItemGetter[Int] {}

    implicit val floatItemGetter: ItemGetter[Float] = new ItemGetter[Float] {}
  }

  /**
   * Represents a connection to a GIAPi based instrument
   *
   * @tparam F
   *   Effect type
   */
  trait GiapiConnection[F[_]] {
    def newGiapiConnection: Resource[F, Giapi[F]]
  }

  /**
   * Algebra to interact with a GIAPI instrument
   *
   * @tparam F
   *   Effect Type
   */
  trait Giapi[F[_]]           {

    /**
     * Returns a value for the status item. If not found or there is an error, an exception could be
     * thrown
     */
    def get[A: ItemGetter](statusItem: String): F[A]

    /**
     * Attempts to read a value. If not found an empty F is returned
     */
    def getO[A: ItemGetter](statusItem: String): F[Option[A]]

    /**
     * Executes a command as defined on GIAPI Note that commands can end in ERROR or COMPLETED Giapi
     * has an extra case where we have a command ACCEPTED and it will complete in the future That
     * makes handling easier with callbacks on Java land but on IO-land it makes more sense to wait
     * for ERROR/COMPLETED and do async calls above this level
     *
     * This decision may be reviewed in the future
     */
    def command(command: Command, timeout: FiniteDuration): F[CommandCallResult]

    /**
     * Returns a stream of values for the status item.
     */
    def stream[A: ItemGetter](statusItem: String): F[Stream[F, A]]

    /**
     * Close the connection
     */
    private[giapi] def close: F[Unit]
  }

  /**
   * Interpreters
   */
  object Giapi {

    final case class StatusStreamer(aggregate: StatusHandlerAggregate, ss: List[StatusService])

    def statusGetter[F[_]: Sync](c: ActiveMQJmsProvider): F[StatusGetter] =
      Sync[F].delay {
        val sg = new StatusGetter("statusGetter")
        sg.startJms(c)
        sg
      }

    private def commandSenderClient[F[_]: Async](
      name: String,
      c:    ActiveMQJmsProvider
    ): Resource[F, CommandSenderClient[F]] =
      CommandSenderClient.commandSenderClient[F](name, c)

    def statusStreamer[F[_]: Sync](
      c:        ActiveMQJmsProvider,
      prefixes: List[String] = Nil
    ): F[StatusStreamer] =
      Sync[F].delay {
        val aggregate = new StatusHandlerAggregate()
        val prefs     = if (prefixes.isEmpty) List(">") else prefixes
        val services  = prefs.map { p =>
          val statusService =
            new StatusService(aggregate, "statusService", p)
          statusService.startJms(c)
          statusService
        }
        StatusStreamer(aggregate, services)
      }

    private def streamItem[F[_]: Async, A: ItemGetter](
      agg:        StatusHandlerAggregate,
      statusItem: String
    ): F[Stream[F, A]] =
      Sync[F].delay {

        def statusHandler(q: Queue[F, A])(dispatcher: Dispatcher[F]): StatusHandler =
          new StatusHandler {

            override def update[B](item: StatusItem[B]): Unit =
              // Check the item name and attempt convert it to A
              if (item.getName === statusItem) {
                ItemGetter[A].value(item.getValue).foreach { a =>
                  dispatcher.unsafeRunAndForget(q.offer(a))
                }
              }

            override def getName: String = "StatusHandler"
          }

        // A trivial resource that binds and unbinds a status handler.
        def bind(q: Queue[F, A]): Resource[F, StatusHandler] =
          // The dispatcher is used only to "offer" items to a Queue, therefore sequential should
          // be OK.
          Dispatcher.sequential[F](true).flatMap { dispatcher =>
            Resource.make(
              Async[F].delay {
                val sh = statusHandler(q)(dispatcher)
                agg.bindStatusHandler(sh)
                sh
              }
            )(sh => Async[F].delay(agg.unbindStatusHandler(sh)))
          }

        // Put the items in a queue as they arrive to the stream
        for {
          q <- Stream.eval(Queue.unbounded[F, A])
          _ <- Stream.resource(bind(q))
          i <- Stream.fromQueueUnterminated(q)
        } yield i
      }

    /**
     * Interpreter on F
     *
     * @param url
     *   Url to connect to
     * @tparam F
     *   Effect type
     */
    def giapiConnection[F[_]: Async](
      name:     String,
      url:      String,
      prefixes: List[String] = Nil
    ): GiapiConnection[F] =
      new GiapiConnection[F] {
        private def giapi(
          sg: StatusGetter,
          cc: CommandSenderClient[F],
          ss: StatusStreamer
        ): Resource[F, Giapi[F]] =
          Resource.make((new Giapi[F] {

            override def get[A: ItemGetter](statusItem: String): F[A] =
              getO[A](statusItem).flatMap {
                case Some(a) => a.pure[F]
                case None    =>
                  Sync[F].raiseError(GiapiException(s"Status item $statusItem not found"))
              }

            def getO[A: ItemGetter](statusItem: String): F[Option[A]] =
              Sync[F].delay {
                val item = sg.getStatusItem[A](statusItem)
                Option(item).map(_.getValue)
              }

            override def command(
              command: Command,
              timeOut: FiniteDuration
            ): F[CommandCallResult] =
              commands.sendCommand(cc, command, timeOut)

            override def stream[A: ItemGetter](statusItem: String): F[Stream[F, A]] =
              streamItem[F, A](ss.aggregate, statusItem)

            override def close: F[Unit] =
              for {
                _ <- Sync[F].delay(sg.stopJms())
                _ <- ss.ss.traverse(ss => Sync[F].delay(ss.stopJms()))
              } yield ()

          }).pure[F])(_.close)

        private def build(c: ActiveMQJmsProvider): Resource[F, Giapi[F]] =
          for {
            sg <- Resource.eval(statusGetter[F](c))
            cc <- commandSenderClient[F](name, c)
            ss <- Resource.eval(statusStreamer[F](c, prefixes))
            g  <- giapi(sg, cc, ss)
          } yield g

        private def amq: Resource[F, ActiveMQJmsProvider] =
          Resource.make(Sync[F].delay {
            val amq = new ActiveMQJmsProvider(url)
            amq.startConnection()
            amq
          })(c => Sync[F].delay(c.stopConnection()))

        def newGiapiConnection: Resource[F, Giapi[F]] =
          amq.flatMap(build)
      }

    /**
     * Interpreter on Id
     */
    def giapiConnectionId: GiapiConnection[Id] = new GiapiConnection[Id] {
      override def newGiapiConnection: Resource[Id, Giapi[Id]] = Resource.pure(new Giapi[Id] {
        override def get[A: ItemGetter](statusItem: String): Id[A] =
          sys.error(s"Cannot read $statusItem")
        override def getO[A: ItemGetter](statusItem: String): Id[Option[A]] = None
        override def stream[A: ItemGetter](statusItem: String): Id[Stream[Id, A]]          =
          sys.error(s"Cannot read $statusItem")
        override def command(command: Command, timeout: FiniteDuration): Id[CommandResult] =
          CommandResult(Response.COMPLETED)
        override def close: Id[Unit]                                                       = ()
      })
    }

    /**
     * Simulator interpreter on IO, Reading items will fail and all commands will succeed
     */
    def simulatedGiapiConnection[F[_]](implicit
      T: Temporal[F],
      F: ApplicativeError[F, Throwable]
    ): GiapiConnection[F] = new GiapiConnection[F] {
      override def newGiapiConnection: Resource[F, Giapi[F]] = Resource.pure(new Giapi[F] {
        override def get[A: ItemGetter](statusItem: String): F[A] =
          F.raiseError(new RuntimeException(s"Cannot read $statusItem"))
        override def getO[A: ItemGetter](statusItem: String): F[Option[A]] = F.pure(None)
        override def stream[A: ItemGetter](statusItem: String): F[Stream[F, A]]               =
          F.pure(Stream.empty.covary[F])
        override def command(command: Command, timeout: FiniteDuration): F[CommandCallResult] =
          if (command.sequenceCommand === SequenceCommand.OBSERVE) {
            T.sleep(timeout) *>
              F.pure(CommandResult(Response.COMPLETED))
          } else {
            T.sleep(5.seconds) *>
              F.pure(CommandResult(Response.COMPLETED))
          }
        override def close: F[Unit]                                                           = F.unit
      })
    }

  }

}
