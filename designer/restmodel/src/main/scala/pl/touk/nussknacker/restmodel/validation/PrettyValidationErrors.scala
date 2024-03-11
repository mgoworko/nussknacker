package pl.touk.nussknacker.restmodel.validation

import org.apache.commons.lang3.StringUtils
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError._
import pl.touk.nussknacker.engine.api.context.{ParameterValidationError, ProcessCompilationError}
import pl.touk.nussknacker.engine.api.generics.ExpressionParseError.ErrorDetails
import pl.touk.nussknacker.engine.api.parameter.ParameterName
import pl.touk.nussknacker.engine.api.util.ReflectUtils
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.graph.node._
import pl.touk.nussknacker.restmodel.validation.ValidationResults.{NodeValidationError, NodeValidationErrorType}

object PrettyValidationErrors {

  def formatErrorMessage(error: ProcessCompilationError): NodeValidationError = {
    val typ = getErrorTypeName(error)

    def node(
        message: String,
        description: String,
        errorType: NodeValidationErrorType.Value = NodeValidationErrorType.SaveAllowed,
        fieldName: Option[String] = None,
        details: Option[ErrorDetails] = None
    ): NodeValidationError = NodeValidationError(typ, message, description, fieldName, errorType, details)

    def handleParameterValidationError(error: ParameterValidationError): NodeValidationError =
      node(error.message, error.description, fieldName = Some(error.paramName.value))

    error match {
      case ExpressionParserCompilationError(message, _, fieldName, _, details) =>
        node(
          s"Failed to parse expression: $message",
          s"There is problem with expression in field $fieldName - it could not be parsed.",
          fieldName = fieldName,
          details = details
        )
      case FragmentParamClassLoadError(fieldName, refClazzName, _) =>
        node(
          "Invalid parameter type.",
          s"Failed to load $refClazzName",
          fieldName =
            Some(qualifiedParamFieldName(paramName = ParameterName(fieldName), subFieldName = Some(TypFieldName)))
        )
      case DuplicatedNodeIds(ids) =>
        node(
          "Two nodes cannot have same id",
          s"Duplicate node ids: ${ids.mkString(", ")}",
          errorType = NodeValidationErrorType.RenderNotAllowed
        )
      case EmptyProcess       => node("Empty scenario", "Scenario is empty, please add some nodes")
      case InvalidRootNode(_) => node("Invalid root node", "Scenario can start only from source node")
      case InvalidTailOfBranch(_) =>
        node(
          "Scenario must end with a sink, processor or fragment",
          "Scenario must end with a sink, processor or fragment"
        )
      case error: IdError =>
        mapIdErrorToNodeError(error)
      case ScenarioNameValidationError(message, description) =>
        node(
          message,
          description,
          fieldName = Some(CanonicalProcess.NameFieldName),
        )
      case SpecificDataValidationError(field, message) => node(message, message, fieldName = Some(field))
      case NonUniqueEdgeType(etype, nodeId) =>
        node(
          "Edges are not unique",
          s"Node $nodeId has duplicate outgoing edges of type: $etype, it cannot be saved properly",
          errorType = NodeValidationErrorType.SaveNotAllowed
        )
      case NonUniqueEdge(nodeId, target) =>
        node(
          "Edges are not unique",
          s"Node $nodeId has duplicate outgoing edges to: $target, it cannot be saved properly",
          errorType = NodeValidationErrorType.SaveNotAllowed
        )
      case LooseNode(nodeIds) =>
        val (message, description) = nodeIds.toList match {
          case nodeId :: Nil =>
            (
              "Loose node",
              s"Node $nodeId is not connected to source, it cannot be saved properly"
            )
          case _ =>
            (
              "Loose nodes",
              s"Nodes ${nodeIds.mkString(", ")} are not connected to source, it cannot be saved properly"
            )
        }
        node(
          message,
          description,
          errorType = NodeValidationErrorType.SaveNotAllowed
        )
      case DisabledNode(nodeId) =>
        node(
          s"Node $nodeId is disabled",
          "Deploying scenario with disabled node can have unexpected consequences",
          NodeValidationErrorType.SaveAllowed
        )

      case MissingParameters(params, _) =>
        node(
          s"Node parameters not filled: ${params.mkString(", ")}",
          s"Please fill missing node parameters: : ${params.mkString(", ")}"
        )

      case pve: ParameterValidationError => handleParameterValidationError(pve)

      // exceptions below should not really happen (unless services change and process becomes invalid)
      case MissingCustomNodeExecutor(id, _) =>
        node(s"Missing custom executor: $id", s"Please check the name of custom executor, $id is not available")
      case MissingService(id, _) =>
        node(s"Missing processor/enricher: $id", s"Please check the name of processor/enricher, $id is not available")
      case MissingSinkFactory(id, _) =>
        node(s"Missing sink: $id", s"Please check the name of sink, $id is not available")
      case MissingSourceFactory(id, _) =>
        node(s"Missing source: $id", s"Please check the name of source, $id is not available")
      case RedundantParameters(params, _) =>
        node(s"Redundant parameters", s"Please omit redundant parameters: ${params.mkString(", ")}")
      case WrongParameters(requiredParameters, passedParameters, _) =>
        node(
          s"Wrong parameters",
          s"Please provide ${requiredParameters.mkString(", ")} instead of ${passedParameters.mkString(", ")}"
        )
      case OverwrittenVariable(varName, _, paramName) =>
        node(
          s"Variable name '$varName' is already defined.",
          "You cannot overwrite variables",
          fieldName = paramName
        )
      case InvalidVariableName(varName, _, paramName) =>
        node(
          s"Variable name '$varName' is not a valid identifier (only letters, numbers or '_', cannot be empty)",
          "Please use only letters, numbers or '_', also identifier cannot be empty.",
          fieldName = paramName
        )
      case NotSupportedExpressionLanguage(languageId, _) =>
        node(s"Language $languageId is not supported", "Currently only SPEL expressions are supported")
      case MissingPart(id)             => node("MissingPart", s"Node $id has missing part")
      case UnsupportedPart(id)         => node("UnsupportedPart", s"Type of node $id is unsupported right now")
      case UnknownFragment(id, nodeId) => node("Unknown fragment", s"Node $nodeId uses fragment $id which is missing")
      case InvalidFragment(id, nodeId) => node("Invalid fragment", s"Node $nodeId uses fragment $id which is invalid")
      case FatalUnknownError(message) =>
        node("Unknown, fatal validation error", s"Fatal error: $message, please check configuration")
      case CannotCreateObjectError(message, nodeId) =>
        node(s"Could not create $nodeId: $message", s"Could not create $nodeId: $message")

      case UnresolvedFragment(id) => node("Unresolved fragment", s"fragment $id encountered, this should not happen")
      case FragmentOutputNotDefined(id, _) => node(s"Output $id not defined", "Please check fragment definition")
      case UnknownFragmentOutput(id, _)    => node(s"Unknown fragment output $id", "Please check fragment definition")
      case DisablingManyOutputsFragment(_) =>
        node(s"Cannot disable fragment with multiple outputs", "Please check fragment definition")
      case DisablingNoOutputsFragment(_) =>
        node(s"Cannot disable fragment with no outputs", "Please check fragment definition")
      case MissingRequiredProperty(paramName, label, _) => missingRequiredProperty(typ, paramName.value, label)
      case UnknownProperty(paramName, _)                => unknownProperty(typ, paramName.value)
      case InvalidPropertyFixedValue(paramName, label, value, values, _) =>
        invalidPropertyFixedValue(typ, paramName.value, label, value, values)
      case CustomNodeError(_, message, paramName) =>
        NodeValidationError(typ, message, message, paramName.map(_.value), NodeValidationErrorType.SaveAllowed, None)
      case e: DuplicateFragmentOutputNames =>
        node(
          s"Fragment output name '${e.duplicatedVarName}' has to be unique",
          "Please check fragment definition"
        )
      case DuplicateFragmentInputParameter(paramName, _) =>
        node(
          s"Parameter name '${paramName.value}' has to be unique",
          "Parameter name not unique",
          fieldName = Some(qualifiedParamFieldName(paramName = paramName, subFieldName = Some(ParameterNameFieldName)))
        )
      case InitialValueNotPresentInPossibleValues(paramName, _) =>
        node(
          s"The initial value provided for parameter '${paramName.value}' is not present in the parameter's possible values list",
          "Please check component definition",
          fieldName = Some(qualifiedParamFieldName(paramName = paramName, subFieldName = Some(InitialValueFieldName)))
        )
      case UnsupportedFixedValuesType(paramName, typ, _) =>
        node(
          s"Fixed values list can only be be provided for type String or Boolean, found: $typ",
          "Please check component definition",
          fieldName = Some(qualifiedParamFieldName(paramName = paramName, subFieldName = Some(TypFieldName)))
        )
      case RequireValueFromEmptyFixedList(paramName, _) =>
        node(
          s"Required parameter '${paramName.value}' cannot be a member of an empty fixed list",
          "Please check component definition",
          fieldName = Some(qualifiedParamFieldName(paramName = paramName, subFieldName = Some(InputModeFieldName)))
        )
      case ExpressionParserCompilationErrorInFragmentDefinition(message, _, paramName, subFieldName, originalExpr) =>
        node(
          s"Failed to parse expression: $message",
          s"There is a problem with expression: $originalExpr",
          fieldName = Some(qualifiedParamFieldName(paramName = paramName, subFieldName = subFieldName))
        )
      case InvalidValidationExpression(message, _, paramName, originalExpr) =>
        node(
          s"Invalid validation expression: $message",
          s"There is a problem with validation expression: $originalExpr",
          fieldName =
            Some(qualifiedParamFieldName(paramName = paramName, subFieldName = Some(ValidationExpressionFieldName)))
        )
      case DictNotDeclared(dictId, _, paramName) =>
        node(
          s"Dictionary not declared: $dictId",
          s"Dictionary not declared: $dictId",
          fieldName = Some(paramName.value)
        )
      case DictEntryWithKeyNotExists(dictId, key, possibleKeys, _, paramName) =>
        node(
          s"Dictionary $dictId doesn't contain entry with key: $key",
          s"Dictionary $dictId possible keys: $possibleKeys",
          fieldName = Some(paramName.value)
        )
      case DictEntryWithLabelNotExists(dictId, label, possibleLabels, _, paramName) =>
        node(
          s"Dictionary $dictId doesn't contain entry with label: $label",
          s"Dictionary $dictId possible labels: $possibleLabels",
          fieldName = Some(paramName.value)
        )
      case DictLabelByKeyResolutionFailed(dictId, key, _, paramName) =>
        node(
          s"Failed to resolve label for key: $key in dict: $dictId",
          s"Dict registry doesn't support fetching of label for dictId: $dictId",
          fieldName = Some(paramName.value)
        )
      case KeyWithLabelExpressionParsingError(keyWithLabel, message, paramName, _) =>
        node(
          s"Error while parsing KeyWithLabel expression: $keyWithLabel",
          message,
          fieldName = Some(paramName.value)
        )
    }
  }

  private def unknownProperty(typ: String, propertyName: String): NodeValidationError =
    NodeValidationError(
      typ = typ,
      message = s"Unknown property $propertyName",
      description = s"Property $propertyName is not known",
      fieldName = Some(propertyName),
      errorType = NodeValidationErrorType.SaveAllowed,
      details = None
    )

  private def missingRequiredProperty(typ: String, fieldName: String, label: Option[String]) = {
    val labelText = getLabel(label)
    NodeValidationError(
      typ = typ,
      message = s"Configured property $fieldName$labelText is missing",
      description = s"Please fill missing property $fieldName$labelText",
      fieldName = Some(fieldName),
      errorType = NodeValidationErrorType.SaveAllowed,
      details = None
    )
  }

  private def invalidPropertyFixedValue(
      typ: String,
      propertyName: String,
      label: Option[String],
      value: String,
      values: List[String]
  ) = {
    val labelText = getLabel(label)
    NodeValidationError(
      typ = typ,
      message = s"Property $propertyName$labelText has invalid value",
      description = s"Expected one of ${values.mkString(", ")}, got: $value.",
      fieldName = Some(propertyName),
      errorType = NodeValidationErrorType.SaveAllowed,
      details = None
    )
  }

  private def getLabel(label: Option[String]) = label match {
    case Some(text) => s" ($text)"
    case None       => StringUtils.EMPTY
  }

  private def getErrorTypeName(error: ProcessCompilationError) = ReflectUtils.simpleNameWithoutSuffix(error.getClass)

  private def mapIdErrorToNodeError(error: IdError) = {
    val validatedObjectType = error match {
      case ScenarioNameError(_, _, isFragment) => if (isFragment) "Fragment" else "Scenario"
      case NodeIdValidationError(_, _)         => "Node"
    }
    val errorSeverity = error match {
      case ScenarioNameError(_, _, _) => NodeValidationErrorType.SaveAllowed
      case NodeIdValidationError(errorType, _) =>
        errorType match {
          case ProcessCompilationError.EmptyValue | IllegalCharactersId(_) => NodeValidationErrorType.RenderNotAllowed
          case _                                                           => NodeValidationErrorType.SaveAllowed
        }
    }
    val fieldName = error match {
      case ScenarioNameError(_, _, _)  => CanonicalProcess.NameFieldName
      case NodeIdValidationError(_, _) => pl.touk.nussknacker.engine.graph.node.IdFieldName
    }
    val message = error.errorType match {
      case ProcessCompilationError.EmptyValue       => s"$validatedObjectType name is mandatory and cannot be empty"
      case ProcessCompilationError.BlankId          => s"$validatedObjectType name cannot be blank"
      case ProcessCompilationError.LeadingSpacesId  => s"$validatedObjectType name cannot have leading spaces"
      case ProcessCompilationError.TrailingSpacesId => s"$validatedObjectType name cannot have trailing spaces"
      case ProcessCompilationError.IllegalCharactersId(illegalCharactersHumanReadable) =>
        s"$validatedObjectType name contains invalid characters. $illegalCharactersHumanReadable are not allowed"
    }
    val description = error.errorType match {
      case ProcessCompilationError.EmptyValue      => s"Empty ${validatedObjectType.toLowerCase} name"
      case ProcessCompilationError.BlankId         => s"Blank ${validatedObjectType.toLowerCase} name"
      case ProcessCompilationError.LeadingSpacesId => s"Leading spaces in ${validatedObjectType.toLowerCase} name"
      case ProcessCompilationError.TrailingSpacesId =>
        s"Trailing spaces in ${validatedObjectType.toLowerCase} name"
      case ProcessCompilationError.IllegalCharactersId(_) =>
        s"Invalid characters in ${validatedObjectType.toLowerCase} name"
    }
    NodeValidationError(
      typ = getErrorTypeName(error),
      message = message,
      description = description,
      fieldName = Some(fieldName),
      errorType = errorSeverity,
      details = None
    )
  }

}
