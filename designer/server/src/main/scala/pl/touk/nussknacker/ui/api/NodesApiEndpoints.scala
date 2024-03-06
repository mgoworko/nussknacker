package pl.touk.nussknacker.ui.api

import cats.data.NonEmptyList
import derevo.circe.{decoder, encoder}
import derevo.derive
import io.circe.generic.JsonCodec
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import org.springframework.util.ClassUtils
import pl.touk.nussknacker.engine.ModelData
import pl.touk.nussknacker.engine.additionalInfo.{AdditionalInfo, MarkdownAdditionalInfo}
import pl.touk.nussknacker.engine.api.CirceUtil._
import pl.touk.nussknacker.engine.api.ProcessAdditionalFields
import pl.touk.nussknacker.engine.api.definition.ParameterEditor
import pl.touk.nussknacker.engine.api.editor.DualEditorMode
import pl.touk.nussknacker.engine.api.graph.{Edge, ProcessProperties, ScenarioGraph}
import pl.touk.nussknacker.engine.api.process.{ProcessName, ProcessingType}
import pl.touk.nussknacker.engine.api.typed.TypingResultDecoder
import pl.touk.nussknacker.engine.api.typed.typing._
import pl.touk.nussknacker.engine.graph.evaluatedparam
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.graph.node.NodeData.nodeDataEncoder
import pl.touk.nussknacker.engine.graph.node.{Enricher, Filter, NodeData}
import pl.touk.nussknacker.engine.graph.service.ServiceRef
import pl.touk.nussknacker.engine.spel.ExpressionSuggestion
import pl.touk.nussknacker.restmodel.BaseEndpointDefinitions
import pl.touk.nussknacker.restmodel.BaseEndpointDefinitions.SecuredEndpoint
import pl.touk.nussknacker.restmodel.definition.{UIParameter, UIValueParameter}
import pl.touk.nussknacker.restmodel.validation.ValidationResults.{NodeValidationError, NodeValidationErrorType}
import pl.touk.nussknacker.security.AuthCredentials
import pl.touk.nussknacker.ui.api.NodesApiEndpoints.Dtos.NodesError.{
  MalformedTypingResult,
  NoProcessingType,
  NoScenario
}
import pl.touk.nussknacker.ui.api.NodesApiEndpoints.{
  malformedTypingResultExample,
  noProcessingTypeExample,
  noScenarioExample
}
import pl.touk.nussknacker.ui.api.TapirCodecs.ScenarioNameCodec._
import pl.touk.nussknacker.ui.api.TypingDtoSchemas.TypedClassSchemaHelper.typedClassTypeSchema
import pl.touk.nussknacker.ui.api.TypingDtoSchemas.TypedDictSchemaHelper.typedDictTypeSchema
import pl.touk.nussknacker.ui.api.TypingDtoSchemas.TypedNullSchemaHelper.typedNullTypeSchema
import pl.touk.nussknacker.ui.api.TypingDtoSchemas.TypedObjectSchemaHelper.typedObjectTypeSchema
import pl.touk.nussknacker.ui.api.TypingDtoSchemas.TypedObjectTypingResultSchemaHelper.typedObjectTypingResultTypeSchema
import pl.touk.nussknacker.ui.api.TypingDtoSchemas.TypedTaggedSchemaHelper.typedTaggedTypeSchema
import pl.touk.nussknacker.ui.api.TypingDtoSchemas.TypedUnionSchemaHelper.typedUnionTypeSchema
import pl.touk.nussknacker.ui.api.TypingDtoSchemas.UnknownSchemaHelper.unknownTypeSchema
import pl.touk.nussknacker.ui.api.TypingDtoSchemas._
import pl.touk.nussknacker.ui.services.BaseHttpService.CustomAuthorizationError
import pl.touk.nussknacker.ui.suggester.CaretPosition2d
import sttp.model.StatusCode.{BadRequest, NotFound, Ok}
import sttp.tapir.EndpointIO.Example
import sttp.tapir.SchemaType.{SString, SchemaWithValue}
import sttp.tapir._
import sttp.tapir.derevo.schema
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe.jsonBody

import scala.language.implicitConversions

class NodesApiEndpoints(auth: EndpointInput[AuthCredentials]) extends BaseEndpointDefinitions {

  import NodesApiEndpoints.Dtos._
  lazy val encoder: Encoder[TypingResult] = TypingResult.encoder

  lazy val nodesAdditionalInfoEndpoint
      : SecuredEndpoint[(ProcessName, NodeData), NodesError, Option[AdditionalInfo], Any] = {
    baseNuApiEndpoint
      .summary("Additional info for provided node")
      .tag("Nodes")
      .post
      .in("nodes" / path[ProcessName]("scenarioName") / "additionalInfo")
      .in(
        jsonBody[NodeData]
          .example(
            Example.of(
              summary = Some("Basic node request"),
              value = Enricher(
                "enricher",
                ServiceRef(
                  "paramService",
                  List(
                    evaluatedparam.Parameter("id", Expression("spel", "'a'"))
                  )
                ),
                "out",
                None
              )
            )
          )
      )
      .out(
        statusCode(Ok).and(
          jsonBody[Option[AdditionalInfo]]
            .example(
              Example.of(
                summary = Some("Additional info for node"),
                value = Some(
                  MarkdownAdditionalInfo(
                    "\nSamples:\n\n| id  | value |\n| --- | ----- |\n| a   | generated |\n| b   | not existent |\n\nResults for a can be found [here](http://touk.pl?id=a)\n"
                  )
                )
              )
            )
        )
      )
      .errorOut(scenarioNotFoundErrorOutput)
      .withSecurity(auth)
  }

  lazy val nodesValidationEndpoint
      : SecuredEndpoint[(ProcessName, NodeValidationRequestDto), NodesError, NodeValidationResultDto, Any] = {
    baseNuApiEndpoint
      .summary("Validate provided Node")
      .tag("Nodes")
      .post
      .in("nodes" / path[ProcessName]("scenarioName") / "validation")
      .in(
        jsonBody[NodeValidationRequestDto]
          .examples(
            List(
              Example.of(
                NodeValidationRequestDto(
                  Filter("id", Expression("spel", "#longValue > 1"), None, None),
                  ProcessProperties.apply(ProcessAdditionalFields(None, Map.empty, "")),
                  Map(
                    "existButString" -> TypingResultInJson(
                      encoder.apply(Typed.apply(Class.forName("java.lang.String")))
                    ),
                    "longValue" -> TypingResultInJson(encoder.apply(Typed.apply(Class.forName("java.lang.Long"))))
                  ),
                  None,
                  None
                ),
                summary = Some("Validate correct Filter node")
              ),
              Example.of(
                NodeValidationRequestDto(
                  Filter("id", Expression("spel", "#existButString"), None, None),
                  ProcessProperties.apply(ProcessAdditionalFields(None, Map.empty, "")),
                  Map(
                    "existButString" -> TypingResultInJson(
                      encoder.apply(Typed.apply(Class.forName("java.lang.String")))
                    ),
                    "longValue" -> TypingResultInJson(encoder.apply(Typed.apply(Class.forName("java.lang.Long"))))
                  ),
                  None,
                  None
                )
              )
            )
          )
      )
      .out(
        statusCode(Ok).and(
          jsonBody[NodeValidationResultDto]
            .examples(
              List(
                Example.of(
                  summary = Some("Node validation without errors"),
                  value = NodeValidationResultDto(
                    None,
                    Some(Typed.apply(Class.forName("java.lang.Boolean"))),
                    List.empty,
                    validationPerformed = true
                  )
                ),
                Example.of(
                  summary = Some("Wrong parameter type"),
                  value = NodeValidationResultDto(
                    None,
                    Some(Unknown),
                    List(
                      NodeValidationError(
                        "ExpressionParserCompilationError",
                        "Failed to parse expression: Bad expression type, expected: Boolean, found: String",
                        "There is problem with expression in field Some($expression) - it could not be parsed.",
                        Some("$expression"),
                        NodeValidationErrorType.SaveAllowed
                      )
                    ),
                    validationPerformed = true
                  )
                )
              )
            )
        )
      )
      .errorOut(
        oneOf[NodesError](
          noScenarioExample,
          malformedTypingResultExample
        )
      )
      .withSecurity(auth)
  }

  lazy val propertiesAdditionalInfoEndpoint
      : SecuredEndpoint[(ProcessName, ProcessProperties), NodesError, Option[AdditionalInfo], Any] = {
    baseNuApiEndpoint
      .summary("Additional info for provided properties")
      .tag("Nodes")
      .post
      .in("properties" / path[ProcessName]("scenarioName") / "additionalInfo")
      .in(
        jsonBody[ProcessProperties]
          .example(
            Example.of(
              ProcessProperties.apply(
                validPropertiesAdditionalFields
              )
            )
          )
      )
      .out(
        statusCode(Ok).and(
          jsonBody[Option[AdditionalInfo]]
            .example(
              Example.of(
                summary = Some("Some additional info for parameters"),
                value = Some(MarkdownAdditionalInfo("2 threads will be used on environment '{scenarioName}'"))
              )
            )
        )
      )
      .errorOut(scenarioNotFoundErrorOutput)
      .withSecurity(auth)
  }

  lazy val propertiesValidationEndpoint
      : SecuredEndpoint[(ProcessName, PropertiesValidationRequestDto), NodesError, NodeValidationResultDto, Any] = {
    baseNuApiEndpoint
      .summary("Validate node properties")
      .tag("Nodes")
      .post
      .in("properties" / path[ProcessName]("scenarioName") / "validation")
      .in(
        jsonBody[PropertiesValidationRequestDto]
          .examples(
            List(
              Example.of(
                summary = Some("Validate proper properties"),
                value = PropertiesValidationRequestDto(
                  validPropertiesAdditionalFields,
                  ProcessName("test")
                )
              ),
              Example.of(
                summary = Some("Validate wrong 'number of threads' property"),
                value = PropertiesValidationRequestDto(
                  ProcessAdditionalFields(
                    None,
                    Map(
                      "parallelism"                 -> "",
                      "checkpointIntervalInSeconds" -> "",
                      "numberOfThreads"             -> "a",
                      "spillStateToDisk"            -> "true",
                      "environment"                 -> "test",
                      "useAsyncInterpretation"      -> ""
                    ),
                    "StreamMetaData"
                  ),
                  ProcessName("test")
                )
              )
            )
          )
      )
      .out(
        statusCode(Ok).and(
          jsonBody[NodeValidationResultDto]
            .examples(
              List(
                Example.of(
                  summary = Some("Validation for proper node"),
                  value = NodeValidationResultDto(None, None, List.empty, validationPerformed = true)
                ),
                Example.of(
                  summary = Some("Validation for properties with errors"),
                  value = NodeValidationResultDto(
                    None,
                    None,
                    List(
                      NodeValidationError(
                        "InvalidPropertyFixedValue",
                        "Property numberOfThreads (Number of threads) has invalid value",
                        "Expected one of 1, 2, got: a.",
                        Some("numberOfThreads"),
                        NodeValidationErrorType.SaveAllowed
                      ),
                      NodeValidationError(
                        "UnknownProperty",
                        "Unknown property parallelism",
                        "Property parallelism is not known",
                        Some("parallelism"),
                        NodeValidationErrorType.SaveAllowed
                      )
                    ),
                    validationPerformed = true
                  )
                )
              )
            )
        )
      )
      .errorOut(scenarioNotFoundErrorOutput)
      .withSecurity(auth)
  }

  lazy val parametersValidationEndpoint: SecuredEndpoint[
    (ProcessingType, ParametersValidationRequestDto),
    NodesError,
    ParametersValidationResultDto,
    Any
  ] = {
    baseNuApiEndpoint
      .summary("Validate given parameters")
      .tag("Nodes")
      .post
      .in("parameters" / path[ProcessingType]("processingType") / "validate")
      .in(
        jsonBody[ParametersValidationRequestDto]
          .example(
            Example.of(
              summary = Some("Parameters validation"),
              value = ParametersValidationRequestDto(
                List(
                  UIValueParameterDto(
                    "condition",
                    TypingResultInJson(encoder.apply(Typed.apply(Class.forName("java.lang.Boolean")))),
                    Expression("spel", "#input.amount > 2")
                  )
                ),
                Map(
                  "input" ->
                    TypingResultInJson(
                      encoder.apply(
                        Typed.record(
                          Map(
                            "amount" ->
                              TypedObjectWithValue.apply(
                                Typed.apply(Class.forName("java.lang.Long")).asInstanceOf[TypedClass],
                                5L
                              )
                          )
                        )
                      )
                    )
                )
              )
            )
          )
      )
      .out(
        statusCode(Ok).and(
          jsonBody[ParametersValidationResultDto]
            .examples(
              List(
                Example.of(
                  ParametersValidationResultDto(
                    List.empty,
                    validationPerformed = true
                  ),
                  summary = Some("Validate correct parameters")
                ),
                Example.of(
                  ParametersValidationResultDto(
                    List(
                      NodeValidationError(
                        "ExpressionParserCompilationError",
                        "Failed to parse expression: Bad expression type, expected: Boolean, found: Long(5)",
                        "There is problem with expression in field Some(condition) - it could not be parsed.",
                        Some("condition"),
                        NodeValidationErrorType.SaveAllowed
                      )
                    ),
                    validationPerformed = true
                  ),
                  summary = Some("Validate incorrect parameters")
                )
              )
            )
        )
      )
      .errorOut(
        oneOf[NodesError](
          noProcessingTypeExample,
          malformedTypingResultExample
        )
      )
      .withSecurity(auth)
  }

  lazy val parametersSuggestionsEndpoint: SecuredEndpoint[
    (ProcessingType, ExpressionSuggestionRequestDto),
    NodesError,
    List[ExpressionSuggestionDto],
    Any
  ] = {
    baseNuApiEndpoint
      .summary("Suggest possible variables")
      .tag("Nodes")
      .post
      .in("parameters" / path[ProcessingType]("processingType") / "suggestions")
      .in(
        jsonBody[ExpressionSuggestionRequestDto]
          .example(
            Example.of(
              ExpressionSuggestionRequestDto(
                Expression("spel", "#inpu"),
                CaretPosition2d(0, 5),
                Map(
                  "input" ->
                    TypingResultInJson(
                      encoder.apply(
                        Typed.record(
                          Map(
                            "amount" ->
                              TypedObjectWithValue.apply(
                                Typed.apply(Class.forName("java.lang.Long")).asInstanceOf[TypedClass],
                                5L
                              )
                          )
                        )
                      )
                    )
                )
              )
            )
          )
      )
      .out(
        statusCode(Ok).and(
          jsonBody[List[ExpressionSuggestionDto]]
            .example(
              Example.of(
                List(
                  ExpressionSuggestionDto(
                    "input",
                    Typed.record(
                      Map(
                        "amount" ->
                          TypedObjectWithValue.apply(
                            Typed.apply(Class.forName("java.lang.Long")).asInstanceOf[TypedClass],
                            5L
                          )
                      )
                    ),
                    fromClass = false,
                    None,
                    List.empty
                  )
                )
              )
            )
        )
      )
      .errorOut(
        oneOf[NodesError](
          noProcessingTypeExample,
          malformedTypingResultExample
        )
      )
      .withSecurity(auth)
  }

  private lazy val scenarioNotFoundErrorOutput: EndpointOutput.OneOf[NodesError, NodesError] =
    oneOf[NodesError](
      oneOfVariantFromMatchType(
        NotFound,
        plainBody[NoScenario]
          .example(
            Example.of(
              summary = Some("No scenario {scenarioName} found"),
              value = NoScenario(ProcessName("'example scenario'"))
            )
          )
      )
    )

  private val validPropertiesAdditionalFields =
    ProcessAdditionalFields(
      None,
      Map(
        "parallelism"                 -> "",
        "checkpointIntervalInSeconds" -> "",
        "numberOfThreads"             -> "2",
        "spillStateToDisk"            -> "true",
        "environment"                 -> "test",
        "useAsyncInterpretation"      -> ""
      ),
      "StreamMetaData"
    )

}

object NodesApiEndpoints {

  object Dtos {

    case class TypingResultInJson(value: Json)

    object TypingResultInJson {
      implicit def apply(typingResultInJson: TypingResultInJson): Json = typingResultInJson.value
      implicit lazy val typingResultInJsonDecoder: Decoder[TypingResultInJson] =
        Decoder.decodeJson.map(TypingResultInJson.apply)
      implicit lazy val typingResultInJsonEncoder: Encoder[TypingResultInJson] =
        Encoder.instance(typingResultInJson => typingResultInJson.value)
      implicit lazy val typingResultInJsonSchema: Schema[TypingResultInJson] = TypingDtoSchemas.typingResult.as
    }

    implicit lazy val scenarioNameSchema: Schema[ProcessName]                         = Schema.derived
    implicit lazy val additionalInfoSchema: Schema[AdditionalInfo]                    = Schema.derived
    implicit lazy val scenarioAdditionalFieldsSchema: Schema[ProcessAdditionalFields] = Schema.derived

    // Request doesn't need valid encoder, apart from examples
    @derive(encoder, decoder, schema)
    final case class NodeValidationRequestDto(
        nodeData: NodeData,
        processProperties: ProcessProperties,
        variableTypes: Map[String, TypingResultInJson],
        branchVariableTypes: Option[Map[String, Map[String, TypingResultInJson]]],
        outgoingEdges: Option[List[Edge]]
    )

    object NodeValidationRequestDto {
      implicit lazy val nodeDataSchema: Schema[NodeData]                    = Schema.anyObject
      implicit lazy val scenarioPropertiesSchema: Schema[ProcessProperties] = Schema.derived.hidden(true)
    }

    // Response doesn't need valid decoder
    @derive(encoder, schema)
    final case class NodeValidationResultDto(
        parameters: Option[List[UIParameter]],
        expressionType: Option[TypingResult],
        validationErrors: List[NodeValidationError],
        validationPerformed: Boolean
    )

    implicit val nodeValidationRequestDtoDecoder: Decoder[NodeValidationResultDto] =
      Decoder.instance[NodeValidationResultDto](_ => throw new IllegalStateException)

    object NodeValidationResultDto {
      implicit lazy val parameterEditorSchema: Schema[ParameterEditor]    = Schema.derived
      implicit lazy val dualEditorSchema: Schema[DualEditorMode]          = Schema.string
      implicit lazy val expressionSchema: Schema[Expression]              = Schema.derived
      implicit lazy val timeSchema: Schema[java.time.temporal.ChronoUnit] = Schema.anyObject

      def apply(node: NodeValidationResult): NodeValidationResultDto = {
        new NodeValidationResultDto(
          parameters = node.parameters,
          expressionType = node.expressionType,
          validationErrors = node.validationErrors,
          validationPerformed = node.validationPerformed
        )
      }

    }

    @derive(schema, encoder, decoder)
    final case class PropertiesValidationRequestDto(
        additionalFields: ProcessAdditionalFields,
        name: ProcessName
    )

    // Request doesn't need valid encoder, apart from examples
    @derive(schema, encoder, decoder)
    final case class ParametersValidationRequestDto(
        parameters: List[UIValueParameterDto],
        variableTypes: Map[String, TypingResultInJson]
    )

    @derive(schema, encoder, decoder)
    final case class ParametersValidationResultDto(
        validationErrors: List[NodeValidationError],
        validationPerformed: Boolean
    )

    // Request doesn't need valid encoder, apart from examples
    @derive(schema, encoder, decoder)
    final case class UIValueParameterDto(
        name: String,
        typ: TypingResultInJson,
        expression: Expression
    )

    implicit lazy val expressionSchema: Schema[Expression]           = Schema.derived
    implicit lazy val caretPosition2dSchema: Schema[CaretPosition2d] = Schema.derived

    // Request doesn't need valid encoder, apart from examples
    @derive(schema, encoder, decoder)
    final case class ExpressionSuggestionRequestDto(
        expression: Expression,
        caretPosition2d: CaretPosition2d,
        variableTypes: Map[String, TypingResultInJson]
    )

//    implicit val expressionSuggestionRequestDtoEncoder: Encoder[ExpressionSuggestionRequestDto] =
//      Encoder.encodeJson.contramap[ExpressionSuggestionRequestDto](_ => throw new IllegalStateException)

    // Response doesn't need valid decoder
    @derive(schema, encoder)
    final case class ExpressionSuggestionDto(
        methodName: String,
        refClazz: TypingResult,
        fromClass: Boolean,
        description: Option[String],
        parameters: List[ParameterDto]
    )

    object ExpressionSuggestionDto {

      def apply(expr: ExpressionSuggestion): ExpressionSuggestionDto = {
        new ExpressionSuggestionDto(
          expr.methodName,
          expr.refClazz,
          expr.fromClass,
          expr.description,
          expr.parameters.map(param => ParameterDto(param.name, param.refClazz))
        )
      }

    }

    implicit val expressionSuggestionDtoDecoder: Decoder[ExpressionSuggestionDto] =
      Decoder.instance[ExpressionSuggestionDto](_ => throw new IllegalStateException)

    // Response doesn't need valid decoder
    @derive(schema, encoder)
    final case class ParameterDto(
        name: String,
        refClazz: TypingResult
    )

    def prepareTypingResultDecoder(modelData: ModelData): Decoder[TypingResult] = {
      new TypingResultDecoder(name =>
        ClassUtils.forName(name, modelData.modelClassLoader.classLoader)
      ).decodeTypingResults
    }

    def prepareTestFromParametersDecoder(modelData: ModelData): Decoder[TestFromParametersRequest] = {
      implicit val typeDecoder: Decoder[TypingResult] = prepareTypingResultDecoder(modelData)
      implicit val testSourceParametersDecoder: Decoder[TestSourceParameters] =
        deriveConfiguredDecoder[TestSourceParameters]
      deriveConfiguredDecoder[TestFromParametersRequest]
    }

    @JsonCodec(encodeOnly = true) final case class TestSourceParameters(
        sourceId: String,
        parameterExpressions: Map[String, Expression]
    )

    @JsonCodec(encodeOnly = true) final case class TestFromParametersRequest(
        sourceParameters: TestSourceParameters,
        scenarioGraph: ScenarioGraph
    )

    @JsonCodec(encodeOnly = true) final case class ParametersValidationRequest(
        parameters: List[UIValueParameter],
        variableTypes: Map[String, TypingResult]
    )

    @JsonCodec(encodeOnly = true) final case class NodeValidationResult(
        // It it used for node parameter adjustment on FE side (see ParametersUtils.ts -> adjustParameters)
        parameters: Option[List[UIParameter]],
        // expressionType is returned to present inferred types of a single, hardcoded parameter of the node
        // We currently support only type inference for an expression in the built-in components: variable and switch
        // and fields of the record-variable and fragment output (we return TypedObjectTypingResult in this case)
        // TODO: We should keep this in a map, instead of TypedObjectTypingResult as it is done in ValidationResult.typingInfo
        //       Thanks to that we could remove some code on the FE side and be closer to support also not built-in components
        expressionType: Option[TypingResult],
        validationErrors: List[NodeValidationError],
        validationPerformed: Boolean
    )

    @JsonCodec(encodeOnly = true) final case class NodeValidationRequest(
        nodeData: NodeData,
        processProperties: ProcessProperties,
        variableTypes: Map[String, TypingResult],
        branchVariableTypes: Option[Map[String, Map[String, TypingResult]]],
        // TODO: remove Option when FE is ready
        // In this request edges are not guaranteed to have the correct "from" field. Normally it's synced with node id but
        // when renaming node, it contains node's id before the rename.
        outgoingEdges: Option[List[Edge]]
    )

    def decodeVariableTypes(
        variableTypes: Map[String, Dtos.TypingResultInJson],
        typingResultDecoder: Decoder[TypingResult]
    ): Either[MalformedTypingResult, Map[String, TypingResult]] = {
      val result: Map[ProcessingType, TypingResult] = variableTypes
        .map { case (key, typingResult) =>
          (key, typingResultDecoder.decodeJson(typingResult))
        }
        .map { case (key, maybeValue) =>
          maybeValue match {
            case Left(failure: DecodingFailure) => return Left(MalformedTypingResult(failure.message))
            case Right(value)                   => (key, value)
          }
        }
      Right(result)
    }

    sealed trait NodesError

    object NodesError {
      final case class NoScenario(scenarioName: ProcessName)            extends NodesError
      final case class NoProcessingType(processingType: ProcessingType) extends NodesError
      final case object NoPermission                                    extends NodesError with CustomAuthorizationError
      final case class MalformedTypingResult(msg: String)               extends NodesError

      private def deserializationNotSupportedException =
        (ignored: Any) => throw new IllegalStateException("Deserializing errors is not supported.")

      implicit val noScenarioCodec: Codec[String, NoScenario, CodecFormat.TextPlain] = {
        Codec.string.map(
          Mapping.from[String, NoScenario](deserializationNotSupportedException)(e =>
            s"No scenario ${e.scenarioName} found"
          )
        )
      }

      implicit val noProcessingTypeCodec: Codec[String, NoProcessingType, CodecFormat.TextPlain] = {
        Codec.string.map(
          Mapping.from[String, NoProcessingType](deserializationNotSupportedException)(e =>
            s"ProcessingType type: ${e.processingType} not found"
          )
        )
      }

      implicit val malformedTypingResultCoded: Codec[String, MalformedTypingResult, CodecFormat.TextPlain] = {
        Codec.string.map(
          Mapping.from[String, MalformedTypingResult](deserializationNotSupportedException)(e =>
            s"The request content was malformed:\n${e.msg}"
          )
        )
      }

    }

  }

  private val noScenarioExample: EndpointOutput.OneOfVariant[NoScenario] =
    oneOfVariantFromMatchType(
      NotFound,
      plainBody[NoScenario]
        .example(
          Example.of(
            summary = Some("No scenario {scenarioName} found"),
            value = NoScenario(ProcessName("'example scenario'"))
          )
        )
    )

  private val malformedTypingResultExample: EndpointOutput.OneOfVariant[MalformedTypingResult] =
    oneOfVariantFromMatchType(
      BadRequest,
      plainBody[MalformedTypingResult]
        .example(
          Example.of(
            summary = Some("Malformed TypingResult sent in request"),
            value = MalformedTypingResult(
              "Couldn't decode value 'WrongType'. Allowed values: 'TypedUnion,TypedDict,TypedObjectTypingResult,TypedTaggedValue,TypedClass,TypedObjectWithValue,TypedNull,Unknown"
            )
          )
        )
    )

  private val noProcessingTypeExample: EndpointOutput.OneOfVariant[NoProcessingType] =
    oneOfVariantFromMatchType(
      NotFound,
      plainBody[NoProcessingType]
        .example(
          Example.of(
            summary = Some("ProcessingType type: {processingType} not found"),
            value = NoProcessingType("'processingType'")
          )
        )
    )

}

object TypingDtoSchemas {

  import pl.touk.nussknacker.engine.api.typed.TypingType
  import pl.touk.nussknacker.engine.api.typed.TypingType.TypingType
  import pl.touk.nussknacker.engine.api.typed.typing._
  import sttp.tapir.Schema.SName
  import sttp.tapir.SchemaType.SProductField
  import sttp.tapir.{FieldName, Schema, SchemaType}

  implicit lazy val typingResult: Schema[TypingResult] = {
    Schema(
      SchemaType.SCoproduct(
        List(
          unknownSchema.hidden(true),
          unknownSchema,
          typedNullSchema,
          typedObjectTypingResultSchema,
          typedDictSchema,
          typedObjectSchema,
          typedClassSchema,
          typedUnionSchema,
          typedTaggedSchema
        ),
        None
      ) {
        case Unknown                              => Some(SchemaWithValue(unknownSchema, Unknown))
        case TypedNull                            => Some(SchemaWithValue(typedNullSchema, TypedNull))
        case typedObject: TypedObjectTypingResult => Some(SchemaWithValue(typedObjectTypingResultSchema, typedObject))
        case typedDict: TypedDict                 => Some(SchemaWithValue(typedDictSchema, typedDict))
        case typedWithValue: TypedObjectWithValue => Some(SchemaWithValue(typedObjectSchema, typedWithValue))
        case typedClass: TypedClass               => Some(SchemaWithValue(typedClassSchema, typedClass))
        case union: TypedUnion                    => Some(SchemaWithValue(typedUnionSchema, union))
        case tagged: TypedTaggedValue             => Some(SchemaWithValue(typedTaggedSchema, tagged))
      }
    )
  }

  implicit lazy val additionalDataValueSchema: Schema[AdditionalDataValue] = Schema.derived

  object TypedObjectTypingResultSchemaHelper {
    sealed trait types

    object types {
      case object TypedObjectTypingResult extends types
    }

    implicit val typedObjectTypingResultTypeSchema: Schema[types] = Schema.derivedEnumeration[types].defaultStringBased
  }

  implicit lazy val typedObjectTypingResultSchema: Schema[TypedObjectTypingResult] = {
    Schema(
      SchemaType.SProduct(
        List(
          sProductFieldForDisplay,
          SProductField[String, TypedObjectTypingResultSchemaHelper.types](
            FieldName("type"),
            typedObjectTypingResultTypeSchema,
            _ => Some(TypedObjectTypingResultSchemaHelper.types.TypedObjectTypingResult)
          ),
          SProductField[String, Map[String, TypingResult]](
            FieldName("fields"),
            Schema.schemaForMap[TypingResult](Schema(SchemaType.SRef(SName("#")))),
            _ => None
          )
        ) :::
          sProductFieldForKlassAndParams
      ),
      Some(SName("TypedObjectTypingResult"))
    )
      .title("TypedObjectTypingResult")
      .as
  }

  object TypedDictSchemaHelper {
    sealed trait types

    object types {
      case object TypedDict extends types
    }

    implicit val typedDictTypeSchema: Schema[types] = Schema.derivedEnumeration[types].defaultStringBased

  }

  implicit lazy val typedDictSchema: Schema[TypedDict] = {
    final case class Dict(id: String, valueType: TypedTaggedValue)
    lazy val dictSchema: Schema[Dict] = Schema.derived
    Schema(
      SchemaType.SProduct(
        List(
          sProductFieldForDisplay,
          SProductField[String, TypedDictSchemaHelper.types](
            FieldName("type"),
            typedDictTypeSchema,
            _ => Some(TypedDictSchemaHelper.types.TypedDict)
          ),
        ) :::
          List(SProductField[String, Dict](FieldName("dict"), dictSchema, _ => None))
      ),
      Some(SName("TypedDict"))
    )
      .title("TypedDict")
      .as
  }

  implicit lazy val typedObjectWithDataSchema: Schema[TypedObjectWithData] =
    Schema.derived.hidden(true)

  object TypedTaggedSchemaHelper {
    sealed trait types

    object types {
      case object TypedTaggedValue extends types
    }

    implicit val typedTaggedTypeSchema: Schema[types] = Schema.derivedEnumeration[types].defaultStringBased
  }

  implicit lazy val typedTaggedSchema: Schema[TypedTaggedValue] = {
    Schema(
      SchemaType.SProduct(
        List(
          SProductField[String, String](FieldName("tag"), Schema.string, tag => Some(tag)),
          sProductFieldForDisplay,
          SProductField[String, TypedTaggedSchemaHelper.types](
            FieldName("type"),
            typedTaggedTypeSchema,
            _ => Some(TypedTaggedSchemaHelper.types.TypedTaggedValue)
          ),
        ) :::
          sProductFieldForKlassAndParams
      ),
      Some(SName("TypedTaggedValue"))
    )
      .title("TypedTaggedValue")
      .as
  }

  object TypedObjectSchemaHelper {
    sealed trait types

    object types {
      case object TypedObjectWithValue extends types
    }

    implicit val typedObjectTypeSchema: Schema[types] = Schema.derivedEnumeration[types].defaultStringBased

  }

  implicit lazy val typedObjectSchema: Schema[TypedObjectWithValue] = {
    Schema(
      SchemaType.SProduct(
        List(
          SProductField[String, Any](FieldName("value"), Schema.any, value => Some(value)),
          sProductFieldForDisplay,
          SProductField[String, TypedObjectSchemaHelper.types](
            FieldName("type"),
            typedObjectTypeSchema,
            _ => Some(TypedObjectSchemaHelper.types.TypedObjectWithValue)
          ),
        ) :::
          sProductFieldForKlassAndParams
      ),
      Some(SName("TypedObjectWithValue"))
    )
      .title("TypedObjectWithValue")
      .as
  }

  object TypedNullSchemaHelper {
    sealed trait types

    object types {
      case object TypedNull extends types
    }

    implicit val typedNullTypeSchema: Schema[types] = Schema.derivedEnumeration[types].defaultStringBased

  }

  implicit lazy val typedNullSchema: Schema[TypedNull.type] =
    Schema(
      SchemaType.SProduct(
        List(
          sProductFieldForDisplay,
          SProductField[String, TypedNullSchemaHelper.types](
            FieldName("type"),
            typedNullTypeSchema,
            _ => Some(TypedNullSchemaHelper.types.TypedNull)
          ),
          SProductField[String, String](
            FieldName("refClazzName"),
            Schema(SString(), isOptional = true),
            refClazzName => Some(refClazzName)
          ),
          SProductField[String, List[TypingResult]](
            FieldName("params"),
            Schema.schemaForIterable[TypingResult, List](
              Schema(SchemaType.SRef(SName("#")))
            ),
            _ => Some(List(Unknown))
          )
        )
      ),
      Some(SName("TypedNull")),
    )
      .title("TypedNull")
      .as

  object UnknownSchemaHelper {
    sealed trait types

    object types {
      case object Unknown extends types
    }

    implicit val unknownTypeSchema: Schema[types] = Schema.derivedEnumeration[types].defaultStringBased

  }

  implicit lazy val unknownSchema: Schema[Unknown.type] =
    Schema(
      SchemaType.SProduct(
        List(
          sProductFieldForDisplay,
          SProductField[String, UnknownSchemaHelper.types](
            FieldName("type"),
            unknownTypeSchema,
            _ => Some(UnknownSchemaHelper.types.Unknown)
          ),
          SProductField[String, String](
            FieldName("refClazzName"),
            Schema(SString(), isOptional = true),
            refClazzName => Some(refClazzName)
          ),
          SProductField[String, List[TypingResult]](
            FieldName("params"),
            Schema.schemaForIterable[TypingResult, List](
              Schema(SchemaType.SRef(SName("#")))
            ),
            _ => Some(List(Unknown))
          )
        )
      ),
      Some(SName("Unknown")),
    )
      .title("Unknown")
      .as

  object TypedUnionSchemaHelper {
    sealed trait types

    object types {
      case object TypedUnion extends types
    }

    implicit val typedUnionTypeSchema: Schema[types] = Schema.derivedEnumeration[types].defaultStringBased

  }

  implicit lazy val typedUnionSchema: Schema[TypedUnion] = {
    Schema(
      SchemaType.SProduct(
        List(
          sProductFieldForDisplay,
          SProductField[String, TypedUnionSchemaHelper.types](
            FieldName("type"),
            typedUnionTypeSchema,
            _ => Some(TypedUnionSchemaHelper.types.TypedUnion)
          ),
          SProductField[String, NonEmptyList[TypingResult]](
            FieldName("union"),
            Schema
              .schemaForArray[TypingResult](Schema(SchemaType.SRef(SName("#"))))
              .copy(isOptional = false)
              .as,
            _ => Some(NonEmptyList(Unknown, List.empty))
          )
        )
      ),
      Some(Schema.SName("TypedUnion"))
    )
      .title("TypedUnion")
      .as
  }

  object TypedClassSchemaHelper {
    sealed trait types

    object types {
      case object TypedClass extends types
    }

    implicit val typedClassTypeSchema: Schema[types] = Schema.derivedEnumeration[types].defaultStringBased

  }

  implicit lazy val typedClassSchema: Schema[TypedClass] = {
    Schema(
      SchemaType.SProduct(
        List(
          sProductFieldForDisplay,
          SProductField[String, TypedClassSchemaHelper.types](
            FieldName("type"),
            typedClassTypeSchema,
            _ => Some(TypedClassSchemaHelper.types.TypedClass)
          ),
        ) :::
          sProductFieldForKlassAndParams
      ),
      Some(SName("TypedClass")),
    )
      .title("TypedClass")
      .as
  }

  private lazy val sProductFieldForDisplay: SProductField[String] =
    SProductField[String, String](
      FieldName("display"),
      Schema(SString(), isOptional = true),
      display => Some(display)
    )

  private lazy val sProductFieldForKlassAndParams: List[SProductField[String]] = {
    List(
      SProductField[String, String](FieldName("refClazzName"), Schema.string, refClazzName => Some(refClazzName)),
      SProductField[String, List[TypingResult]](
        FieldName("params"),
        Schema.schemaForIterable[TypingResult, List](
          Schema(SchemaType.SRef(SName("#")))
        ),
        _ => Some(List(Unknown))
      )
    )
  }

}
