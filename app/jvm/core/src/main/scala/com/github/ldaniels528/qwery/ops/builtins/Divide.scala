package com.github.ldaniels528.qwery.ops.builtins

import com.github.ldaniels528.qwery.ops.{Expression, Scope}

/**
  * Represents a division operation
  * @param a the left quantity
  * @param b the right quantity
  */
case class Divide(a: Expression, b: Expression) extends Expression {
  override def evaluate(scope: Scope): Option[Double] = for {
    x <- a.getAsDouble(scope)
    y <- b.getAsDouble(scope) if y != 0
  } yield x / y
}
