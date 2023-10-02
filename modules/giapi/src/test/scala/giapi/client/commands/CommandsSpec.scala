// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client.commands

import cats.kernel.laws.discipline._
import giapi.client.GiapiArbitraries
import munit.DisciplineSuite

/**
 * Tests Command typeclasses
 */
final class CommandsSpec extends DisciplineSuite with GiapiArbitraries {
  checkAll("Eq[Configuration]", EqTests[Configuration].eqv)
  checkAll("Monoid[Configuration]", MonoidTests[Configuration].monoid)
}
