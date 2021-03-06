package com.github.ldaniels528.qwery.ops.sql

import com.github.ldaniels528.qwery.ops.{Executable, ResultSet, Scope, Variable, VariableRef}

/**
  * Represents a Variable Declaration
  * @author lawrence.daniels@gmail.com
  */
case class Declare(variableRef: VariableRef, typeName: String) extends Executable {

  override def execute(scope: Scope): ResultSet = {
    scope += Variable(variableRef.name, value = None)
    ResultSet.affected()
  }

}
