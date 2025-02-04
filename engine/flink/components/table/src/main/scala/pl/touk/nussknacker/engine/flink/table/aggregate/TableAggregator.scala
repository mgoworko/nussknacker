package pl.touk.nussknacker.engine.flink.table.aggregate

import enumeratum._
import org.apache.flink.table.functions.{BuiltInFunctionDefinition, BuiltInFunctionDefinitions}
import org.apache.flink.table.types.logical.LogicalTypeRoot
import org.apache.flink.table.types.logical.LogicalTypeRoot._
import pl.touk.nussknacker.engine.api.NodeId
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError.CustomNodeError
import pl.touk.nussknacker.engine.api.typed.typing.TypingResult
import pl.touk.nussknacker.engine.flink.table.aggregate.TableAggregationFactory.aggregateByParamName
import pl.touk.nussknacker.engine.flink.table.utils.simulateddatatype.{
  SimulatedCallContext,
  ToSimulatedDataTypeConverter
}

/*
   TODO: add remaining aggregations functions:
     - LISTAGG
     - ARRAY_AGG
     - COLLECT

   TODO: unify aggregator function definitions with unbounded-streaming ones. Current duplication may lead to
     inconsistency in naming and may be confusing for users

   TODO: add distinct parameter - but not for First and Last aggregators
 */
object TableAggregator extends Enum[TableAggregator] {
  val values = findValues

  case object Average extends TableAggregator {
    override val displayName: String                                        = "Average"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.AVG
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = Some(numericAggregationsAllowedTypes)
  }

  case object Count extends TableAggregator {
    override val displayName: String                                        = "Count"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.COUNT
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = None
  }

  case object Max extends TableAggregator {
    override val displayName: String                                        = "Max"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.MAX
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = Some(minMaxAllowedTypes)
  }

  case object Min extends TableAggregator {
    override val displayName: String                                        = "Min"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.MIN
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = Some(minMaxAllowedTypes)
  }

  case object First extends TableAggregator {
    override val displayName: String                                        = "First"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.FIRST_VALUE
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = Some(firstLastAllowedTypes)
  }

  case object Last extends TableAggregator {
    override val displayName: String                                        = "Last"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.LAST_VALUE
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = Some(firstLastAllowedTypes)
  }

  case object Sum extends TableAggregator {
    override val displayName: String                                        = "Sum"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.SUM
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = Some(numericAggregationsAllowedTypes)
  }

  case object PopulationStandardDeviation extends TableAggregator {
    override val displayName: String                                        = "Population standard deviation"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.STDDEV_POP
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = Some(numericAggregationsAllowedTypes)
  }

  case object SampleStandardDeviation extends TableAggregator {
    override val displayName: String                                        = "Sample standard deviation"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.STDDEV_SAMP
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = Some(numericAggregationsAllowedTypes)
  }

  case object PopulationVariance extends TableAggregator {
    override val displayName: String                                        = "Population variance"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.VAR_POP
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = Some(numericAggregationsAllowedTypes)
  }

  case object SampleVariance extends TableAggregator {
    override val displayName: String                                        = "Sample variance"
    override def flinkFunctionDefinition: BuiltInFunctionDefinition         = BuiltInFunctionDefinitions.VAR_SAMP
    override def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]] = Some(numericAggregationsAllowedTypes)
  }

  private val minMaxAllowedTypes = List(
    TINYINT,
    SMALLINT,
    INTEGER,
    BIGINT,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    VARCHAR,
    DECIMAL,
    TIME_WITHOUT_TIME_ZONE,
    DATE,
    TIMESTAMP_WITHOUT_TIME_ZONE,
    TIMESTAMP_WITH_LOCAL_TIME_ZONE
  )

  // As of Flink 1.19, time-related types are not supported in FIRST_VALUE aggregate function.
  // See: https://issues.apache.org/jira/browse/FLINK-15867
  // See AggFunctionFactory.createFirstValueAggFunction
  private val firstLastAllowedTypes = List(
    TINYINT,
    SMALLINT,
    INTEGER,
    BIGINT,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    VARCHAR,
    DECIMAL
  )

  private val numericAggregationsAllowedTypes = List(
    TINYINT,
    SMALLINT,
    INTEGER,
    BIGINT,
    FLOAT,
    DOUBLE,
    DECIMAL
  )

}

sealed trait TableAggregator extends EnumEntry {

  val displayName: String

  def flinkFunctionDefinition: BuiltInFunctionDefinition

  val flinkFunctionName: String = flinkFunctionDefinition.getName

  def inputAllowedTypesConstraint: Option[List[LogicalTypeRoot]]

  def inferOutputType(inputType: TypingResult)(
      implicit nodeId: NodeId
  ): Either[ProcessCompilationError, TypingResult] = {
    def validateUsingAllowedTypesConstraint() = inputAllowedTypesConstraint
      .map { allowedTypes =>
        if (ToSimulatedDataTypeConverter.toDataType(inputType).getLogicalType.isAnyOf(allowedTypes: _*)) Right(())
        else Left(())
      }
      .getOrElse(Right(()))

    val result = for {
      _          <- validateUsingAllowedTypesConstraint()
      outputType <- SimulatedCallContext.validateAgainstFlinkFunctionDefinition(flinkFunctionDefinition, inputType)
    } yield outputType

    result.fold(
      _ =>
        Left(
          CustomNodeError(
            s"Invalid type: '${inputType.withoutValue.display}' for selected aggregator: '$displayName'.",
            Some(aggregateByParamName)
          )
        ),
      Right(_)
    )
  }

}
