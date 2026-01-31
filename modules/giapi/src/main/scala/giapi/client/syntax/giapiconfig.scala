// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client.syntax

import giapi.client.GiapiConfig

trait GiapiConfigOps:
  extension [A](a: A)
    def configValue(implicit c: GiapiConfig[A]): String =
      c.configValue(a)

object giapiconfig extends GiapiConfigOps
