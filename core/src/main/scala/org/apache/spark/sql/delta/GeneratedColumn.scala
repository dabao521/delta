/*
 * Copyright (2021) The Delta Lake Project Authors.
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
 */

package org.apache.spark.sql.delta

// scalastyle:off import.ordering.noEmptyLine
import java.util.Locale

import org.apache.spark.sql.delta.actions.{Metadata, Protocol}
import org.apache.spark.sql.delta.files.{TahoeBatchFileIndex, TahoeFileIndex}
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.schema.SchemaUtils.quoteIdentifier
import org.apache.spark.sql.delta.sources.{DeltaSourceUtils, DeltaSQLConf}
import org.apache.spark.sql.delta.sources.DeltaSourceUtils.GENERATION_EXPRESSION_METADATA_KEY
import org.apache.spark.sql.delta.util.AnalysisHelper
import org.apache.hadoop.fs.Path

import org.apache.spark.sql.{AnalysisException, Column, Dataset, SparkSession}
import org.apache.spark.sql.catalyst.catalog.BucketSpec
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, LogicalPlan, Project}
import org.apache.spark.sql.catalyst.util.CaseInsensitiveMap
import org.apache.spark.sql.connector.expressions.{BucketTransform, Transform}
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.datasources.{HadoopFsRelation, LogicalRelation}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DataType, DateType, DoubleType, FloatType, IntegerType, Metadata => FieldMetadata, MetadataBuilder, StringType, StructField, StructType, TimestampType}

/**
 * Provide utility methods to implement Generated Columns for Delta. Users can use the following
 * SQL syntax to create a table with generated columns.
 *
 * ```
 * CREATE TABLE table_identifier(
 * column_name column_type,
 * column_name column_type GENERATED ALWAYS AS ( generation_expr ),
 * ...
 * )
 * USING delta
 * [ PARTITIONED BY (partition_column_name, ...) ]
 * ```
 *
 * This is an example:
 * ```
 * CREATE TABLE foo(
 * id bigint,
 * type string,
 * subType string GENERATED ALWAYS AS ( SUBSTRING(type FROM 0 FOR 4) ),
 * data string,
 * eventTime timestamp,
 * day date GENERATED ALWAYS AS ( days(eventTime) )
 * USING delta
 * PARTITIONED BY (type, day)
 * ```
 *
 * When writing to a table, for these generated columns:
 * - If the output is missing a generated column, we will add an expression to generate it.
 * - If a generated column exists in the output, in other words, we will add a constraint to ensure
 *   the given value doesn't violate the generation expression.
 */
object GeneratedColumn extends DeltaLogging with AnalysisHelper {

  val MIN_WRITER_VERSION = 4

  def satisfyGeneratedColumnProtocol(protocol: Protocol): Boolean = {
    protocol.minWriterVersion >= MIN_WRITER_VERSION
  }

  /**
   * Whether the field contains the generation expression. Note: this doesn't mean the column is a
   * generated column. A column is a generated column only if the table's
   * `minWriterVersion` >= `GeneratedColumn.MIN_WRITER_VERSION` and the column metadata contains
   * generation expressions. Use the other `isGeneratedColumn` to check whether it's a generated
   * column instead.
   */
  private[delta] def isGeneratedColumn(field: StructField): Boolean = {
    field.metadata.contains(GENERATION_EXPRESSION_METADATA_KEY)
  }

  /** Whether a column is a generated column. */
  def isGeneratedColumn(protocol: Protocol, field: StructField): Boolean = {
    satisfyGeneratedColumnProtocol(protocol) && isGeneratedColumn(field)
  }

  /**
   * Whether any generation expressions exist in the schema. Note: this doesn't mean the table
   * contains generated columns. A table has generated columns only if its
   * `minWriterVersion` >= `GeneratedColumn.MIN_WRITER_VERSION` and some of columns in the table
   * schema contain generation expressions. Use `enforcesGeneratedColumns` to check generated
   * column tables instead.
   */
  def hasGeneratedColumns(schema: StructType): Boolean = {
    schema.exists(isGeneratedColumn)
  }

  /**
   * Returns the generated columns of a table. A column is a generated column requires:
   * - The table writer protocol >= GeneratedColumn.MIN_WRITER_VERSION;
   * - It has a generation expression in the column metadata.
   */
  def getGeneratedColumns(snapshot: Snapshot): Seq[StructField] = {
    if (satisfyGeneratedColumnProtocol(snapshot.protocol)) {
      snapshot.metadata.schema.partition(isGeneratedColumn)._1
    } else {
      Nil
    }
  }

  /**
   * Whether the table has generated columns. A table has generated columns only if its
   * `minWriterVersion` >= `GeneratedColumn.MIN_WRITER_VERSION` and some of columns in the table
   * schema contain generation expressions.
   *
   * As Spark will propagate column metadata storing the generation expression through
   * the entire plan, old versions that don't support generated columns may create tables whose
   * schema contain generation expressions. However, since these old versions has a lower writer
   * version, we can use the table's `minWriterVersion` to identify such tables and treat them as
   * normal tables.
   *
   * @param protocol the table protocol.
   * @param metadata the table metadata.
   */
  def enforcesGeneratedColumns(protocol: Protocol, metadata: Metadata): Boolean = {
    satisfyGeneratedColumnProtocol(protocol) && metadata.schema.exists(isGeneratedColumn)
  }

  /** Return the generation expression from a field metadata if any. */
  def getGenerationExpressionStr(metadata: FieldMetadata): Option[String] = {
    if (metadata.contains(GENERATION_EXPRESSION_METADATA_KEY)) {
      Some(metadata.getString(GENERATION_EXPRESSION_METADATA_KEY))
    } else {
      None
    }
  }

  /**
   * Return the generation expression from a field if any. This method doesn't check the protocl.
   * The caller should make sure the table writer protocol meets `satisfyGeneratedColumnProtocol`
   * before calling method.
   */
  def getGenerationExpression(field: StructField): Option[Expression] = {
    getGenerationExpressionStr(field.metadata).map { exprStr =>
      parseGenerationExpression(SparkSession.active, exprStr)
    }
  }

  /** Return the generation expression from a field if any. */
  private def getGenerationExpressionStr(field: StructField): Option[String] = {
    getGenerationExpressionStr(field.metadata)
  }

  /** Parse a generation expression string and convert it to an [[Expression]] object. */
  private def parseGenerationExpression(spark: SparkSession, exprString: String): Expression = {
    spark.sessionState.sqlParser.parseExpression(exprString)
  }

  /**
   * If the schema contains generated columns, check the following unsupported cases:
   * - Refer to a non-existent column or another generated column.
   * - Use an unsupported expression.
   * - The expression type is not the same as the column type.
   */
  def validateGeneratedColumns(spark: SparkSession, schema: StructType): Unit = {
    val (generatedColumns, normalColumns) = schema.partition(isGeneratedColumn)
    // Create a fake relation using the normal columns and add a project with generation expressions
    // on top of it to ask Spark to analyze the plan. This will help us find out the following
    // errors:
    // - Refer to a non existent column in a generation expression.
    // - Refer to a generated column in another one.
    val df = Dataset.ofRows(spark, new LocalRelation(StructType(normalColumns).toAttributes))
    val selectExprs = generatedColumns.map { f =>
      getGenerationExpressionStr(f) match {
        case Some(exprString) =>
          val expr = parseGenerationExpression(df.sparkSession, exprString)
          new Column(expr).alias(f.name)
        case None =>
          // Should not happen
          throw new IllegalStateException(
            s"Cannot find the expressions in the generated column ${f.name}")
      }
    }
    val dfWithExprs = try {
      df.select(selectExprs: _*)
    } catch {
      case e: AnalysisException if e.getMessage != null =>
        val regexCandidates = Seq(
          "Column.*?does not exist. Did you mean one of the following?.*?".r,
          "cannot resolve.*?given input columns:.*?".r
        )
        if (regexCandidates.exists(_.findFirstMatchIn(e.getMessage).isDefined)) {
          throw DeltaErrors.generatedColumnsReferToWrongColumns(e)
        } else {
          throw e
        }
    }
    // Check whether the generation expressions are valid
    dfWithExprs.queryExecution.analyzed.transformAllExpressions {
      case expr: Alias =>
        // Alias will be non deterministic if it points to a non deterministic expression.
        // Skip `Alias` to provide a better error for a non deterministic expression.
        expr
      case expr @ (_: GetStructField | _: GetArrayItem) =>
        // The complex type extractors don't have a function name, so we need to check them
        // separately. `GetMapValue` and `GetArrayStructFields` are not supported because Delta
        // Invariant Check doesn't support them.
        expr
      case expr: UserDefinedExpression =>
        throw DeltaErrors.generatedColumnsUDF(expr)
      case expr if !expr.deterministic =>
        throw DeltaErrors.generatedColumnsNonDeterministicExpression(expr)
      case expr if expr.isInstanceOf[AggregateExpression] =>
        throw DeltaErrors.generatedColumnsAggregateExpression(expr)
      case expr if !SupportedGenerationExpressions.expressions.contains(expr.getClass) =>
        throw DeltaErrors.generatedColumnsUnsupportedExpression(expr)
    }
    // Compare the columns types defined in the schema and the expression types.
    generatedColumns.zip(dfWithExprs.schema).foreach { case (column, expr) =>
      if (column.dataType != expr.dataType) {
        throw DeltaErrors.generatedColumnsTypeMismatch(column.name, column.dataType, expr.dataType)
      }
    }
  }

  def getGeneratedColumnsAndColumnsUsedByGeneratedColumns(schema: StructType): Set[String] = {
    val generationExprs = schema.flatMap { col =>
      getGenerationExpressionStr(col).map { exprStr =>
        val expr = parseGenerationExpression(SparkSession.active, exprStr)
        new Column(expr).alias(col.name)
      }
    }
    if (generationExprs.isEmpty) {
      return Set.empty
    }

    val df = Dataset.ofRows(SparkSession.active, new LocalRelation(schema.toAttributes))
    val generatedColumnsAndColumnsUsedByGeneratedColumns =
      df.select(generationExprs: _*).queryExecution.analyzed match {
        case Project(exprs, _) =>
          exprs.flatMap {
            case Alias(expr, column) =>
              expr.references.map {
                case a: AttributeReference => a.name
                case other =>
                  // Should not happen since the columns should be resolved
                throw new IllegalStateException(s"Expected AttributeReference but got $other")
              }.toSeq :+ column
            case other =>
              // Should not happen since we use `Alias` expressions.
              throw new IllegalStateException(s"Expected Alias but got $other")
          }
        case other =>
          // Should not happen since `select` should use `Project`.
          throw new IllegalStateException(s"Expected Project but got $other")
      }
    // Converting columns to lower case is fine since Delta's schema is always case insensitive.
    generatedColumnsAndColumnsUsedByGeneratedColumns.map(_.toLowerCase(Locale.ROOT)).toSet
  }

  /**
   * Try to get `OptimizablePartitionExpression`s of a data column when a partition column is
   * defined as a generated column and refers to this data column.
   *
   * @param schema the table schema
   * @param partitionSchema the partition schema. If a partition column is defined as a generated
   *                        column, its column metadata should contain the generation expression.
   */
  def getOptimizablePartitionExpressions(
      schema: StructType,
      partitionSchema: StructType): Map[String, Seq[OptimizablePartitionExpression]] = {
    val partitionGenerationExprs = partitionSchema.flatMap { col =>
      getGenerationExpressionStr(col).map { exprStr =>
        val expr = parseGenerationExpression(SparkSession.active, exprStr)
        new Column(expr).alias(col.name)
      }
    }
    if (partitionGenerationExprs.isEmpty) {
      return Map.empty
    }

    val spark = SparkSession.active
    val nameEquality = spark.sessionState.analyzer.resolver
    val nameNormalizer: String => String =
      if (spark.sessionState.conf.caseSensitiveAnalysis) x => x else _.toLowerCase(Locale.ROOT)

    /**
     * If the column `a`'s type matches the expected type, call `func` to create
     * `OptimizablePartitionExpression`. Returns a normalized column name with its
     * `OptimizablePartitionExpression`
     */
    def checkTypeAndCreateExpr(
        a: AttributeReference,
        expectedType: DataType)(
        func: => OptimizablePartitionExpression):
      Option[(String, OptimizablePartitionExpression)] = {
      // Technically, we should only refer to a column in the table schema. Check the column name
      // here just for safety.
      if (a.dataType == expectedType &&
          schema.exists(f => nameEquality(f.name, a.name) && f.dataType == expectedType)) {
        // `a.name` comes from the generation expressions which users may use different cases. We
        // need to normalize it to the same case so that we can group expressions for the same
        // column name together.
        Some(nameNormalizer(a.name) -> func)
      } else {
        None
      }
    }

    val df = Dataset.ofRows(SparkSession.active, new LocalRelation(schema.toAttributes))
    val extractedPartitionExprs =
      df.select(partitionGenerationExprs: _*).queryExecution.analyzed match {
        case Project(exprs, _) =>
          exprs.flatMap {
            case Alias(expr, partColName) =>
              expr match {
                case Cast(a: AttributeReference, DateType, _, _) =>
                  checkTypeAndCreateExpr(a, TimestampType)(DatePartitionExpr(partColName)).orElse(
                    checkTypeAndCreateExpr(a, DateType)(DatePartitionExpr(partColName)))
                case Year(a: AttributeReference) =>
                  checkTypeAndCreateExpr(a, DateType)(YearPartitionExpr(partColName))
                case Year(Cast(a: AttributeReference, DateType, _, _)) =>
                  checkTypeAndCreateExpr(a, TimestampType)(
                    YearPartitionExpr(partColName)).orElse(
                    checkTypeAndCreateExpr(a, DateType)(YearPartitionExpr(partColName)))
                case Month(Cast(a: AttributeReference, DateType, _, _)) =>
                  checkTypeAndCreateExpr(a, TimestampType)(MonthPartitionExpr(partColName))
                case DateFormatClass(
                  Cast(a: AttributeReference, TimestampType, _, _), StringLiteral(format), _) =>
                    format match {
                      case DATE_FORMAT_YEAR_MONTH =>
                        checkTypeAndCreateExpr(a, DateType)(
                          DateFormatPartitionExpr(partColName, DATE_FORMAT_YEAR_MONTH))
                      case _ => None
                    }
                case DateFormatClass(a: AttributeReference, StringLiteral(format), _) =>
                  format match {
                    case DATE_FORMAT_YEAR_MONTH =>
                      checkTypeAndCreateExpr(a, TimestampType)(
                        DateFormatPartitionExpr(partColName, DATE_FORMAT_YEAR_MONTH))
                    case DATE_FORMAT_YEAR_MONTH_DAY_HOUR =>
                      checkTypeAndCreateExpr(a, TimestampType)(
                        DateFormatPartitionExpr(partColName, DATE_FORMAT_YEAR_MONTH_DAY_HOUR))
                    case _ => None
                  }
                case DayOfMonth(Cast(a: AttributeReference, DateType, _, _)) =>
                  checkTypeAndCreateExpr(a, TimestampType)(DayPartitionExpr(partColName))
                case Hour(a: AttributeReference, _) =>
                  checkTypeAndCreateExpr(a, TimestampType)(HourPartitionExpr(partColName))
                case Substring(a: AttributeReference, IntegerLiteral(pos), IntegerLiteral(len)) =>
                  checkTypeAndCreateExpr(a, StringType)(
                    SubstringPartitionExpr(partColName, pos, len))
                case _ => None
              }
            case other =>
              // Should not happen since we use `Alias` expressions.
              throw new IllegalStateException(s"Expected Alias but got $other")
          }
        case other =>
          // Should not happen since `select` should use `Project`.
          throw new IllegalStateException(s"Expected Project but got $other")
      }
    extractedPartitionExprs.groupBy(_._1).map { case (name, group) =>
      val groupedExprs = group.map(_._2)
      val mergedExprs = mergePartitionExpressionsIfPossible(groupedExprs)
      if (log.isDebugEnabled) {
        logDebug(s"Optimizable partition expressions for column $name:")
        mergedExprs.foreach(expr => logDebug(expr.toString))
      }
      name -> mergedExprs
    }
  }

  /**
   * Merge multiple partition expressions into one if possible. For example, users may define
   * three partitions columns, `year`, `month` and `day`, rather than defining a single `date`
   * partition column. Hence, we need to take the multiple partition columns into a single
   * part to consider when optimizing queries.
   */
  private def mergePartitionExpressionsIfPossible(
      exprs: Seq[OptimizablePartitionExpression]): Seq[OptimizablePartitionExpression] = {
    def isRedundantPartitionExpr(f: OptimizablePartitionExpression): Boolean = {
      f.isInstanceOf[YearPartitionExpr] ||
        f.isInstanceOf[MonthPartitionExpr] ||
        f.isInstanceOf[DayPartitionExpr] ||
        f.isInstanceOf[HourPartitionExpr]
    }

    // Take the first option because it's safe to drop other duplicate partition expressions
    val year = exprs.collect { case y: YearPartitionExpr => y }.headOption
    val month = exprs.collect { case m: MonthPartitionExpr => m }.headOption
    val day = exprs.collect { case d: DayPartitionExpr => d }.headOption
    val hour = exprs.collect { case h: HourPartitionExpr => h }.headOption
    (year ++ month ++ day ++ hour) match {
      case Seq(
          year: YearPartitionExpr,
          month: MonthPartitionExpr,
          day: DayPartitionExpr,
          hour: HourPartitionExpr) =>
        exprs.filterNot(isRedundantPartitionExpr) :+
          YearMonthDayHourPartitionExpr(year.yearPart, month.monthPart, day.dayPart, hour.hourPart)
      case Seq(year: YearPartitionExpr, month: MonthPartitionExpr, day: DayPartitionExpr) =>
        exprs.filterNot(isRedundantPartitionExpr) :+
          YearMonthDayPartitionExpr(year.yearPart, month.monthPart, day.dayPart)
      case Seq(year: YearPartitionExpr, month: MonthPartitionExpr) =>
        exprs.filterNot(isRedundantPartitionExpr) :+
          YearMonthPartitionExpr(year.yearPart, month.monthPart)
      case _ =>
        exprs
    }
  }

  def partitionFilterOptimizationEnabled(spark: SparkSession): Boolean = {
    spark.sessionState.conf
      .getConf(DeltaSQLConf.GENERATED_COLUMN_PARTITION_FILTER_OPTIMIZATION_ENABLED)
  }


  /**
   * Try to generate partition filters from data filters if possible.
   *
   * @param delta the logical plan that outputs the same attributes as the table schema. This will
   *              be used to resolve auto generated expressions.
   */
  def generatePartitionFilters(
      spark: SparkSession,
      snapshot: Snapshot,
      dataFilters: Seq[Expression],
      delta: LogicalPlan): Seq[Expression] = {
    if (!satisfyGeneratedColumnProtocol(snapshot.protocol)) {
      return Nil
    }
    if (snapshot.metadata.optimizablePartitionExpressions.isEmpty) {
      return Nil
    }

    val optimizablePartitionExpressions =
      if (spark.sessionState.conf.caseSensitiveAnalysis) {
        snapshot.metadata.optimizablePartitionExpressions
      } else {
        CaseInsensitiveMap(snapshot.metadata.optimizablePartitionExpressions)
      }

    /**
     * Preprocess the data filter such as reordering to ensure the column name appears on the left
     * and the literal appears on the right.
     */
    def preprocess(filter: Expression): Expression = filter match {
      case LessThan(lit: Literal, a: AttributeReference) =>
        GreaterThan(a, lit)
      case LessThanOrEqual(lit: Literal, a: AttributeReference) =>
        GreaterThanOrEqual(a, lit)
      case EqualTo(lit: Literal, a: AttributeReference) =>
        EqualTo(a, lit)
      case GreaterThan(lit: Literal, a: AttributeReference) =>
        LessThan(a, lit)
      case GreaterThanOrEqual(lit: Literal, a: AttributeReference) =>
        LessThanOrEqual(a, lit)
      case e => e
    }

    /**
     * Find the `OptimizablePartitionExpression`s of column `a` and apply them to get the partition
     * filters.
     */
    def toPartitionFilter(
        a: AttributeReference,
        func: (OptimizablePartitionExpression) => Option[Expression]): Seq[Expression] = {
      optimizablePartitionExpressions.get(a.name).toSeq.flatMap { exprs =>
        exprs.flatMap(expr => func(expr))
      }
    }

    val partitionFilters = dataFilters.flatMap { filter =>
      preprocess(filter) match {
        case LessThan(a: AttributeReference, lit: Literal) =>
          toPartitionFilter(a, _.lessThan(lit))
        case LessThanOrEqual(a: AttributeReference, lit: Literal) =>
          toPartitionFilter(a, _.lessThanOrEqual(lit))
        case EqualTo(a: AttributeReference, lit: Literal) =>
          toPartitionFilter(a, _.equalTo(lit))
        case GreaterThan(a: AttributeReference, lit: Literal) =>
          toPartitionFilter(a, _.greaterThan(lit))
        case GreaterThanOrEqual(a: AttributeReference, lit: Literal) =>
          toPartitionFilter(a, _.greaterThanOrEqual(lit))
        case IsNull(a: AttributeReference) =>
          toPartitionFilter(a, _.isNull)
        case _ => Nil
      }
    }

    val resolvedPartitionFilters = resolveReferencesForExpressions(spark, partitionFilters, delta)

    if (log.isDebugEnabled) {
      logDebug("User provided data filters:")
      dataFilters.foreach(f => logDebug(f.sql))
      logDebug("Auto generated partition filters:")
      partitionFilters.foreach(f => logDebug(f.sql))
      logDebug("Resolved generated partition filters:")
      resolvedPartitionFilters.foreach(f => logDebug(f.sql))
    }

    val executionId = Option(spark.sparkContext.getLocalProperty(SQLExecution.EXECUTION_ID_KEY))
      .getOrElse("unknown")
    recordDeltaEvent(
      snapshot.deltaLog,
      "delta.generatedColumns.optimize",
      data = Map(
        "executionId" -> executionId,
        "triggered" -> resolvedPartitionFilters.nonEmpty
      ))

    resolvedPartitionFilters
  }

  private val DATE_FORMAT_YEAR_MONTH = "yyyy-MM"
  private val DATE_FORMAT_YEAR_MONTH_DAY_HOUR = "yyyy-MM-dd-HH"
}