/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import java.sql.{Date, Timestamp}

import org.apache.spark.sql.catalyst.CatalystTypeConverters
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeGenContext, GeneratedExpressionCode}
import org.apache.spark.sql.catalyst.util.DateUtils
import org.apache.spark.sql.types._

object Literal {
  def apply(v: Any): Literal = v match {
    case i: Int => Literal(i, IntegerType)
    case l: Long => Literal(l, LongType)
    case d: Double => Literal(d, DoubleType)
    case f: Float => Literal(f, FloatType)
    case b: Byte => Literal(b, ByteType)
    case s: Short => Literal(s, ShortType)
    case s: String => Literal(UTF8String(s), StringType)
    case b: Boolean => Literal(b, BooleanType)
    case d: BigDecimal => Literal(Decimal(d), DecimalType.Unlimited)
    case d: java.math.BigDecimal => Literal(Decimal(d), DecimalType.Unlimited)
    case d: Decimal => Literal(d, DecimalType.Unlimited)
    case t: Timestamp => Literal(t, TimestampType)
    case d: Date => Literal(DateUtils.fromJavaDate(d), DateType)
    case a: Array[Byte] => Literal(a, BinaryType)
    case null => Literal(null, NullType)
    case _ =>
      throw new RuntimeException("Unsupported literal type " + v.getClass + " " + v)
  }

  def create(v: Any, dataType: DataType): Literal = {
    Literal(CatalystTypeConverters.convertToCatalyst(v), dataType)
  }
}

/**
 * An extractor that matches non-null literal values
 */
object NonNullLiteral {
  def unapply(literal: Literal): Option[(Any, DataType)] = {
    Option(literal.value).map(_ => (literal.value, literal.dataType))
  }
}

/**
 * Extractor for retrieving Int literals.
 */
object IntegerLiteral {
  def unapply(a: Any): Option[Int] = a match {
    case Literal(a: Int, IntegerType) => Some(a)
    case _ => None
  }
}

/**
 * In order to do type checking, use Literal.create() instead of constructor
 */
case class Literal protected (value: Any, dataType: DataType) extends LeafExpression {

  override def foldable: Boolean = true
  override def nullable: Boolean = value == null

  override def toString: String = if (value != null) value.toString else "null"

  override def equals(other: Any): Boolean = other match {
    case o: Literal =>
      dataType.equals(o.dataType) &&
        (value == null && null == o.value || value != null && value.equals(o.value))
    case _ => false
  }

  override def eval(input: Row): Any = value

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    // change the isNull and primitive to consts, to inline them
    if (value == null) {
      ev.isNull = "true"
      ev.primitive = ctx.defaultValue(dataType)
      ""
    } else {
      dataType match {
        case BooleanType =>
          ev.isNull = "false"
          ev.primitive = value.toString
          ""
        case FloatType =>  // This must go before NumericType
          val v = value.asInstanceOf[Float]
          if (v.isNaN || v.isInfinite) {
            super.genCode(ctx, ev)
          } else {
            ev.isNull = "false"
            ev.primitive = s"${value}f"
            ""
          }
        case DoubleType =>  // This must go before NumericType
          val v = value.asInstanceOf[Double]
          if (v.isNaN || v.isInfinite) {
            super.genCode(ctx, ev)
          } else {
            ev.isNull = "false"
            ev.primitive = s"${value}"
            ""
          }

        case ByteType | ShortType =>  // This must go before NumericType
          ev.isNull = "false"
          ev.primitive = s"(${ctx.javaType(dataType)})$value"
          ""
        case dt: NumericType if !dt.isInstanceOf[DecimalType] =>
          ev.isNull = "false"
          ev.primitive = value.toString
          ""
        // eval() version may be faster for non-primitive types
        case other =>
          super.genCode(ctx, ev)
      }
    }
  }
}

// TODO: Specialize
case class MutableLiteral(var value: Any, dataType: DataType, nullable: Boolean = true)
    extends LeafExpression {

  def update(expression: Expression, input: Row): Unit = {
    value = expression.eval(input)
  }

  override def eval(input: Row): Any = value
}
