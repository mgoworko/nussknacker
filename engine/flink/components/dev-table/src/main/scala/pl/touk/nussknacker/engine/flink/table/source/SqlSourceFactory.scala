package pl.touk.nussknacker.engine.flink.table.source

import cats.data.NonEmptySet
import pl.touk.nussknacker.engine.api.component.ProcessingMode
import pl.touk.nussknacker.engine.api.component.ProcessingMode.AllowedProcessingModes
import pl.touk.nussknacker.engine.api.context.ValidationContext
import pl.touk.nussknacker.engine.api.context.transformation.{
  DefinedEagerParameter,
  NodeDependencyValue,
  SingleInputDynamicComponent
}
import pl.touk.nussknacker.engine.api.definition._
import pl.touk.nussknacker.engine.api.parameter.ParameterName
import pl.touk.nussknacker.engine.api.process.{BasicContextInitializer, Source, SourceFactory}
import pl.touk.nussknacker.engine.api.{NodeId, Params}
import pl.touk.nussknacker.engine.flink.table.SqlDataSourcesDefinition
import pl.touk.nussknacker.engine.flink.table.extractor.DataSourceTableDefinition
import pl.touk.nussknacker.engine.flink.table.source.SqlSourceFactory._

class SqlSourceFactory(defs: SqlDataSourcesDefinition) extends SingleInputDynamicComponent[Source] with SourceFactory {

  override type State = DataSourceTableDefinition

  private val tableNameParamDeclaration = {
    val possibleTableParamValues = defs.tableDefinitions
      .map(c => FixedExpressionValue(s"'${c.tableName}'", c.tableName))
    ParameterDeclaration
      .mandatory[String](tableNameParamName)
      .withCreator(
        modify = _.copy(editor =
          Some(FixedValuesParameterEditor(FixedExpressionValue.nullFixedValue +: possibleTableParamValues))
        )
      )
  }

  override def contextTransformation(context: ValidationContext, dependencies: List[NodeDependencyValue])(
      implicit nodeId: NodeId
  ): this.ContextTransformationDefinition = {
    case TransformationStep(Nil, _) =>
      NextParameters(
        parameters = tableNameParamDeclaration.createParameter() :: Nil,
        errors = List.empty,
        state = None
      )
    case TransformationStep((`tableNameParamName`, DefinedEagerParameter(tableName: String, _)) :: Nil, _) =>
      val selectedTable = getSelectedTableUnsafe(tableName)
      val typingResult  = selectedTable.schemaTypingResult
      val initializer   = new BasicContextInitializer(typingResult)
      FinalResults.forValidation(context, Nil, Some(selectedTable))(initializer.validationContext)
  }

  override def implementation(
      params: Params,
      dependencies: List[NodeDependencyValue],
      finalStateOpt: Option[State]
  ): Source = {
    val selectedTable = finalStateOpt.getOrElse(
      throw new IllegalStateException("Unexpected (not defined) final state determined during parameters validation")
    )
    new SqlSource(SqlDataSourceDefinition(selectedTable, defs.sqlStatements))
  }

  override def nodeDependencies: List[NodeDependency] = List(TypedNodeDependency[NodeId])

  override val allowedProcessingModes: AllowedProcessingModes =
    AllowedProcessingModes.SetOf(NonEmptySet.one(ProcessingMode.UnboundedStream))

  private def getSelectedTableUnsafe(tableName: String): DataSourceTableDefinition =
    defs.tableDefinitions
      .find(_.tableName == tableName)
      .getOrElse(throw new IllegalStateException("Table with selected name not found."))

}

object SqlSourceFactory {
  val tableNameParamName: ParameterName = ParameterName("Table")
}
