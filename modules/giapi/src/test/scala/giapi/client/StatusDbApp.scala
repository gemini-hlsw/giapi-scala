// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client

import cats.effect.*
import cats.syntax.all.*

import scala.concurrent.duration.*

// Use for local testing of status streaming
object StatusDbApp extends IOApp {

  private def usage: IO[ExitCode] =
    IO.println(
      "Usage: StatusDbExample <activemq-url> <filters> <item1> [<item2> ...]"
    ).as(ExitCode.Error)

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case url :: filterArg :: head :: tail =>
        val items   = head :: tail
        val filters = filterArg match {
          case "items" => items
          case other   => other.split(",").toList.map(_.trim).filter(_.nonEmpty)
        }
        val db      = Resource.make(
          GiapiStatusDb.newStatusDb[IO](url, items, filters)
        )(_.close)

        db.use { sdb =>
          for {
            _ <- IO.println(s"Connected to $url")
            _ <- IO.println(s"Filters:  ${filters.mkString(", ")}")
            _ <- IO.println(s"Items:    ${items.mkString(", ")}")
            _ <- IO.println("Initial values:")
            _ <- items.traverse_ { i =>
                   sdb.optional(i).flatMap(v => IO.println(s"  $i = $v"))
                 }
            _ <- IO.println("Streaming updates (Ctrl-C to stop):")
            _ <- sdb.discrete
                   .evalMap(snapshot => IO.println(s"  snapshot = $snapshot"))
                   .interruptAfter(10.minutes)
                   .compile
                   .drain
          } yield ExitCode.Success
        }

      case _ => usage
    }

}
