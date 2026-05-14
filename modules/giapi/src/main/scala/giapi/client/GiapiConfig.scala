// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package giapi.client

import java.util.Locale

// Produce a configuration string for Giapi.
trait GiapiConfig[T] {
  def configValue(t: T): String
}

object GiapiConfig {
  implicit val stringConfig: GiapiConfig[String] = t => t
  implicit val intConfig: GiapiConfig[Int]       = _.toString
  implicit val doubleConfig: GiapiConfig[Double] = d => "%1.6f".formatLocal(Locale.US, d)
  implicit val floatConfig: GiapiConfig[Float]   = d => "%1.6f".formatLocal(Locale.US, d)

  @inline
  def apply[A](implicit instance: GiapiConfig[A]): GiapiConfig[A] = instance

  def instance[A](f: A => String): GiapiConfig[A] = new GiapiConfig[A] {
    def configValue(t: A) = f(t)
  }
}
