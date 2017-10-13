/**
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.ksql.codegen;

import io.confluent.ksql.function.KsqlFunction;
import io.confluent.ksql.function.KsqlFunctionRegistry;
import io.confluent.ksql.function.udf.Kudf;
import io.confluent.ksql.parser.tree.AstVisitor;
import io.confluent.ksql.parser.tree.Expression;
import io.confluent.ksql.parser.tree.*;
import io.confluent.ksql.util.ExpressionMetadata;
import io.confluent.ksql.util.ExpressionTypeManager;
import io.confluent.ksql.util.SchemaUtil;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.codehaus.commons.compiler.CompilerFactoryFactory;
import org.codehaus.commons.compiler.IExpressionEvaluator;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CodeGenRunner {

  public Map<String, Class> getParameterInfo(final Expression expression, final Schema schema,
                                             final KsqlFunctionRegistry ksqlFunctionRegistry) {
    Visitor visitor = new Visitor(schema, ksqlFunctionRegistry);
    visitor.process(expression, null);
    return visitor.parameterMap;
  }

  public ExpressionMetadata buildCodeGenFromParseTree(
      final Expression expression,
      final Schema schema,
      final KsqlFunctionRegistry ksqlFunctionRegistry) throws Exception {
    CodeGenRunner codeGenRunner = new CodeGenRunner();
    Map<String, Class> parameterMap = codeGenRunner.getParameterInfo(expression, schema,
                                                                     ksqlFunctionRegistry);

    String[] parameterNames = new String[parameterMap.size()];
    Class[] parameterTypes = new Class[parameterMap.size()];
    int[] columnIndexes = new int[parameterMap.size()];
    Kudf[] kudfObjects = new Kudf[parameterMap.size()];

    int index = 0;
    for (String parameterName : parameterMap.keySet()) {
      parameterNames[index] = parameterName;
      parameterTypes[index] = parameterMap.get(parameterName);
      columnIndexes[index] = SchemaUtil.getFieldIndexByName(schema, parameterName);
      if (columnIndexes[index] < 0) {
        kudfObjects[index] = (Kudf) parameterMap.get(parameterName).newInstance();
      } else {
        kudfObjects[index] = null;
      }
      index++;
    }

    String javaCode = new SqlToJavaVisitor().process(expression, schema, ksqlFunctionRegistry);

    IExpressionEvaluator ee = CompilerFactoryFactory.getDefaultCompilerFactory().newExpressionEvaluator();

    // The expression will have two "int" parameters: "a" and "b".
    ee.setParameters(parameterNames, parameterTypes);

    // And the expression (i.e. "result") type is also "int".
    ExpressionTypeManager expressionTypeManager = new ExpressionTypeManager(schema, ksqlFunctionRegistry);
    Schema expressionType = expressionTypeManager.getExpressionType(expression);

    ee.setExpressionType(SchemaUtil.getJavaType(expressionType));

    // And now we "cook" (scan, parse, compile and load) the fabulous expression.
    ee.cook(javaCode);

    return new ExpressionMetadata(ee, columnIndexes, kudfObjects, expressionType);
  }

  private class Visitor extends AstVisitor<Object, Object> {

    final Schema schema;
    final Map<String, Class> parameterMap;
    final KsqlFunctionRegistry ksqlFunctionRegistry;

    Visitor(Schema schema, KsqlFunctionRegistry ksqlFunctionRegistry) {
      this.schema = schema;
      this.parameterMap = new HashMap<>();
      this.ksqlFunctionRegistry = ksqlFunctionRegistry;
    }

    protected Object visitLikePredicate(LikePredicate node, Object context) {
      process(node.getValue(), null);
      return null;
    }

    protected Object visitFunctionCall(FunctionCall node, Object context) {
      String functionName = node.getName().getSuffix();
      KsqlFunction ksqlFunction = ksqlFunctionRegistry.getFunction(functionName);
      parameterMap.put(node.getName().getSuffix(),
                       ksqlFunction.getKudfClass());
      for (Expression argExpr : node.getArguments()) {
        process(argExpr, null);
      }
      return null;
    }

    protected Object visitArithmeticBinary(ArithmeticBinaryExpression node, Object context) {
      process(node.getLeft(), null);
      process(node.getRight(), null);
      return null;
    }

    protected Object visitIsNotNullPredicate(IsNotNullPredicate node, Object context) {
      return process(node.getValue(), context);
    }

    protected Object visitIsNullPredicate(IsNullPredicate node, Object context) {
      return process(node.getValue(), context);
    }

    protected Object visitLogicalBinaryExpression(LogicalBinaryExpression node, Object context) {
      process(node.getLeft(), null);
      process(node.getRight(), null);
      return null;
    }

    @Override
    protected Object visitComparisonExpression(ComparisonExpression node, Object context) {
      process(node.getLeft(), null);
      process(node.getRight(), null);
      return null;
    }

    @Override
    protected Object visitNotExpression(NotExpression node, Object context) {
      return process(node.getValue(), null);
    }

    @Override
    protected Object visitDereferenceExpression(DereferenceExpression node, Object context) {
      Optional<Field> schemaField = SchemaUtil.getFieldByName(schema, node.toString());
      if (!schemaField.isPresent()) {
        throw new RuntimeException(
            "Cannot find the select field in the available fields: " + node.toString());
      }
      parameterMap.put(schemaField.get().name().replace(".", "_"),
                       SchemaUtil.getJavaType(schemaField.get().schema()));
      return null;
    }

    @Override
    protected Object visitCast(Cast node, Object context) {

      process(node.getExpression(), context);
      return null;
    }

    @Override
    protected Object visitSubscriptExpression(SubscriptExpression node, Object context) {
      String arrayBaseName = node.getBase().toString();
      Optional<Field> schemaField = SchemaUtil.getFieldByName(schema, arrayBaseName);
      if (!schemaField.isPresent()) {
        throw new RuntimeException(
            "Cannot find the select field in the available fields: " + arrayBaseName);
      }
      parameterMap.put(schemaField.get().name().replace(".", "_"),
                       SchemaUtil.getJavaType(schemaField.get().schema()));
      process(node.getIndex(), context);
      return null;
    }

    @Override
    protected Object visitQualifiedNameReference(QualifiedNameReference node, Object context) {
      Optional<Field> schemaField = SchemaUtil.getFieldByName(schema, node.getName().getSuffix());
      if (!schemaField.isPresent()) {
        throw new RuntimeException(
            "Cannot find the select field in the available fields: " + node.getName().getSuffix());
      }
      parameterMap.put(schemaField.get().name().replace(".", "_"),
                       SchemaUtil.getJavaType(schemaField.get().schema()));
      return null;
    }
  }

}
