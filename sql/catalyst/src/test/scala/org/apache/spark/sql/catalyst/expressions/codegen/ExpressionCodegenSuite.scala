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

package org.apache.spark.sql.catalyst.expressions.codegen

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types.IntegerType

class ExpressionCodegenSuite extends SparkFunSuite {

  test("Returns eliminated subexpressions for expression") {
    val ctx = new CodegenContext()
    val subExpr = Add(Literal(1), Literal(2))
    val exprs = Seq(Add(subExpr, Literal(3)), Add(subExpr, Literal(4)))

    ctx.generateExpressions(exprs, doSubexpressionElimination = true)
    val subexpressions = ExpressionCodegen.getSubExprInChildren(ctx, exprs(0))
    assert(subexpressions.length == 1 && subexpressions(0) == subExpr)
  }

  test("Gets parameters for subexpressions") {
    val ctx = new CodegenContext()
    val subExprs = Seq(
      Add(Literal(1), AttributeReference("a", IntegerType, nullable = false)()), // non-nullable
      Add(Literal(2), AttributeReference("b", IntegerType, nullable = true)()))  // nullable

    ctx.subExprEliminationExprs.put(subExprs(0), SubExprEliminationState("false", "value1"))
    ctx.subExprEliminationExprs.put(subExprs(1), SubExprEliminationState("isNull2", "value2"))

    val subExprCodes = ExpressionCodegen.getSubExprCodes(ctx, subExprs)
    val subVars = subExprs.zip(subExprCodes).map { case (expr, exprCode) =>
      ExprInputVar(exprCode, expr.dataType, expr.nullable)
    }
    val params = ExpressionCodegen.prepareFunctionParams(ctx, subVars)
    assert(params.length == 3)
    assert(params(0) == Tuple2("value1", "int value1"))
    assert(params(1) == Tuple2("value2", "int value2"))
    assert(params(2) == Tuple2("isNull2", "boolean isNull2"))
  }

  test("Returns input variables for expression: current variables") {
    val ctx = new CodegenContext()
    val currentVars = Seq(
      ExprCode("", isNull = "false", value = "value1"),             // evaluated
      ExprCode("", isNull = "isNull2", value = "value2"),           // evaluated
      ExprCode("fake code;", isNull = "isNull3", value = "value3")) // not evaluated
    ctx.currentVars = currentVars
    ctx.INPUT_ROW = null

    val expr = If(Literal(false),
      Add(BoundReference(0, IntegerType, nullable = false),
          BoundReference(1, IntegerType, nullable = true)),
        BoundReference(2, IntegerType, nullable = true))

    val inputVars = ExpressionCodegen.getInputVarsForChildren(ctx, expr)
    // Only two evaluated variables included.
    assert(inputVars.length == 2)
    assert(inputVars(0).dataType == IntegerType && inputVars(0).nullable == false)
    assert(inputVars(1).dataType == IntegerType && inputVars(1).nullable == true)
    assert(inputVars(0).exprCode == currentVars(0))
    assert(inputVars(1).exprCode == currentVars(1))

    val params = ExpressionCodegen.prepareFunctionParams(ctx, inputVars)
    assert(params.length == 3)
    assert(params(0) == Tuple2("value1", "int value1"))
    assert(params(1) == Tuple2("value2", "int value2"))
    assert(params(2) == Tuple2("isNull2", "boolean isNull2"))
  }

  test("Returns input variables for expression: deferred variables") {
    val ctx = new CodegenContext()

    // The referred column is not evaluated yet. But it depends on an evaluated column from
    // other operator.
    val currentVars = Seq(ExprCode("fake code;", isNull = "isNull1", value = "value1"))

    // currentVars(0) depends on this evaluated column.
    currentVars(0).inputVars = Seq(ExprInputVar(ExprCode("", isNull = "isNull2", value = "value2"),
      dataType = IntegerType, nullable = true))
    ctx.currentVars = currentVars
    ctx.INPUT_ROW = null

    val expr = Add(Literal(1), BoundReference(0, IntegerType, nullable = false))
    val inputVars = ExpressionCodegen.getInputVarsForChildren(ctx, expr)
    assert(inputVars.length == 1)
    assert(inputVars(0).dataType == IntegerType && inputVars(0).nullable == true)

    val params = ExpressionCodegen.prepareFunctionParams(ctx, inputVars)
    assert(params.length == 2)
    assert(params(0) == Tuple2("value2", "int value2"))
    assert(params(1) == Tuple2("isNull2", "boolean isNull2"))
  }

  test("Returns input rows for expression") {
    val ctx = new CodegenContext()
    ctx.currentVars = null
    ctx.INPUT_ROW = "i"

    val expr = Add(BoundReference(0, IntegerType, nullable = false),
      BoundReference(1, IntegerType, nullable = true))
    val inputRows = ExpressionCodegen.getInputRowsForChildren(ctx, expr)
    assert(inputRows.length == 1)
    assert(inputRows(0) == "i")
  }

  test("Returns input rows for expression: deferred expression") {
    val ctx = new CodegenContext()

    // The referred column is not evaluated yet. But it depends on an input row from
    // other operator.
    val currentVars = Seq(ExprCode("fake code;", isNull = "isNull1", value = "value1"))
    currentVars(0).inputRow = "inputadaptor_row1"
    ctx.currentVars = currentVars
    ctx.INPUT_ROW = null

    val expr = Add(Literal(1), BoundReference(0, IntegerType, nullable = false))
    val inputRows = ExpressionCodegen.getInputRowsForChildren(ctx, expr)
    assert(inputRows.length == 1)
    assert(inputRows(0) == "inputadaptor_row1")
  }

  test("Returns both input rows and variables for expression") {
    val ctx = new CodegenContext()
    // 5 input variables in currentVars:
    //   1 evaluated variable (value1).
    //   3 not evaluated variables.
    //     value2 depends on an evaluated column from other operator.
    //     value3 depends on an input row from other operator.
    //     value4 depends on a not evaluated yet column from other operator.
    //   1 null indicating to use input row "i".
    val currentVars = Seq(
      ExprCode("", isNull = "false", value = "value1"),
      ExprCode("fake code;", isNull = "isNull2", value = "value2"),
      ExprCode("fake code;", isNull = "isNull3", value = "value3"),
      ExprCode("fake code;", isNull = "isNull4", value = "value4"),
      null)
    // value2 depends on this evaluated column.
    currentVars(1).inputVars = Seq(ExprInputVar(ExprCode("", isNull = "isNull5", value = "value5"),
      dataType = IntegerType, nullable = true))
    // value3 depends on an input row "inputadaptor_row1".
    currentVars(2).inputRow = "inputadaptor_row1"
    // value4 depends on another not evaluated yet column.
    currentVars(3).inputVars = Seq(ExprInputVar(ExprCode("fake code;",
      isNull = "isNull6", value = "value6"), dataType = IntegerType, nullable = true))
    ctx.currentVars = currentVars
    ctx.INPUT_ROW = "i"

    // expr: if (false) { value1 + value2 } else { (value3 + value4) + i[5] }
    val expr = If(Literal(false),
      Add(BoundReference(0, IntegerType, nullable = false),
          BoundReference(1, IntegerType, nullable = true)),
      Add(Add(BoundReference(2, IntegerType, nullable = true),
              BoundReference(3, IntegerType, nullable = true)),
          BoundReference(4, IntegerType, nullable = true))) // this is based on input row "i".

    // input rows: "i", "inputadaptor_row1".
    val inputRows = ExpressionCodegen.getInputRowsForChildren(ctx, expr)
    assert(inputRows.length == 2)
    assert(inputRows(0) == "inputadaptor_row1")
    assert(inputRows(1) == "i")

    // input variables: value1 and value5
    val inputVars = ExpressionCodegen.getInputVarsForChildren(ctx, expr)
    assert(inputVars.length == 2)

    // value1 has inlined isNull "false", so don't need to include it in the params.
    val inputVarParams = ExpressionCodegen.prepareFunctionParams(ctx, inputVars)
    assert(inputVarParams.length == 3)
    assert(inputVarParams(0) == Tuple2("value1", "int value1"))
    assert(inputVarParams(1) == Tuple2("value5", "int value5"))
    assert(inputVarParams(2) == Tuple2("isNull5", "boolean isNull5"))
  }

  test("isLiteral: literals") {
    val literals = Seq(
      ExprCode("", "", "true"),
      ExprCode("", "", "false"),
      ExprCode("", "", "1"),
      ExprCode("", "", "-1"),
      ExprCode("", "", "1L"),
      ExprCode("", "", "-1L"),
      ExprCode("", "", "1.0f"),
      ExprCode("", "", "-1.0f"),
      ExprCode("", "", "0.1f"),
      ExprCode("", "", "-0.1f"),
      ExprCode("", "", """"string""""),
      ExprCode("", "", "(byte)-1"),
      ExprCode("", "", "(short)-1"),
      ExprCode("", "", "null"))

    literals.foreach(l => assert(ExpressionCodegen.isLiteral(l) == true))
  }

  test("isLiteral: non literals") {
    val variables = Seq(
      ExprCode("", "", "var1"),
      ExprCode("", "", "_var2"),
      ExprCode("", "", "$var3"),
      ExprCode("", "", "v1a2r3"),
      ExprCode("", "", "_1v2a3r"),
      ExprCode("", "", "$1v2a3r"))

    variables.foreach(v => assert(ExpressionCodegen.isLiteral(v) == false))
  }
}
