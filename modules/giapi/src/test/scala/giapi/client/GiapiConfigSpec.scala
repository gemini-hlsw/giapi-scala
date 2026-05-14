// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client

import munit.CatsEffectSuite

import java.util.Locale

import GiapiConfig.given

final class GiapiConfigSpec extends CatsEffectSuite {

  test("GiapiConfig basic types") {
    Locale.setDefault(Locale.GERMANY)
    assertEquals(GiapiConfig[String].configValue("dummy"), "dummy")
    assertEquals(GiapiConfig[Int].configValue(1234), "1234")
    assertEquals(GiapiConfig[Double].configValue(3.1415), "3.141500")
    assertEquals(GiapiConfig[Double].configValue(3.1415f), "3.141500")
  }

}
