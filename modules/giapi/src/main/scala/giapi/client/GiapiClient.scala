// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client

import edu.gemini.aspen.giapi.commands.Activity
import edu.gemini.aspen.giapi.commands.SequenceCommand
import giapi.client.commands.Command
import giapi.client.commands.CommandCallResult
import giapi.client.commands.Configuration

import scala.concurrent.duration.*

/////////////////////////////////////////////////////////////////
// The GiapiClient comprises the common commands for such clients
/////////////////////////////////////////////////////////////////
trait GiapiClient[F[_]] {
  import GiapiClient.DefaultCommandTimeout

  def giapi: Giapi[F]

  def test: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.TEST, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def init: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.INIT, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def datum: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.DATUM, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def park: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.PARK, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def verify: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.VERIFY, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def endVerify: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.END_VERIFY, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def guide: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.GUIDE, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def endGuide: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.END_GUIDE, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def observe[A: GiapiConfig](dataLabel: A, timeout: FiniteDuration): F[CommandCallResult] =
    giapi.command(Command(
                    SequenceCommand.OBSERVE,
                    Activity.PRESET_START,
                    Configuration.single(commands.DataLabelCfg, dataLabel)
                  ),
                  timeout
    )

  def endObserve: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.END_OBSERVE, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def pause: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.PAUSE, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def continue: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.CONTINUE, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def stop: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.STOP, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def abort: F[CommandCallResult] =
    giapi.command(Command(SequenceCommand.ABORT, Activity.PRESET_START, Configuration.Zero),
                  DefaultCommandTimeout
    )

  def genericApply(configuration: Configuration): F[CommandCallResult] =
    genericApply(configuration, DefaultCommandTimeout)

  def genericApply(configuration: Configuration, timeout: FiniteDuration): F[CommandCallResult] =
    giapi.command(Command(
                    SequenceCommand.APPLY,
                    Activity.PRESET_START,
                    configuration
                  ),
                  timeout
    )
}

object GiapiClient {
  val DefaultCommandTimeout: FiniteDuration = 60.seconds
}
