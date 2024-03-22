package pl.touk.nussknacker.restmodel

import cats.data.NonEmptySet
import io.circe.generic.JsonCodec
import io.circe.generic.extras.ConfiguredJsonCodec
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, DecodingFailure, Encoder}
import pl.touk.nussknacker.engine.api.component.ComponentType.ComponentType
import pl.touk.nussknacker.engine.api.component.ProcessingMode.AllowedProcessingModes
import pl.touk.nussknacker.engine.api.component.{
  ComponentGroupName,
  ComponentId,
  DesignerWideComponentId,
  ProcessingMode
}
import pl.touk.nussknacker.engine.api.deployment.ProcessAction
import pl.touk.nussknacker.engine.api.process.ProcessName
import sttp.tapir.{Schema, SchemaType}

import java.net.URI
import java.time.Instant
import scala.collection.immutable.SortedSet

package object component {

  import io.circe.generic.extras.semiauto._
  import pl.touk.nussknacker.engine.api.CirceUtil._
  import pl.touk.nussknacker.restmodel.codecs.URICodecs._

  type NodeId = String

  @ConfiguredJsonCodec sealed trait NodeUsageData {
    def nodeId: NodeId
  }

  object NodeUsageData {
    final case class FragmentUsageData(fragmentNodeId: String, nodeId: NodeId) extends NodeUsageData

    final case class ScenarioUsageData(nodeId: NodeId) extends NodeUsageData
  }

  object ComponentLink {
    val DocumentationId           = "documentation"
    val DocumentationTile: String = "Documentation"
    val documentationIcon: URI    = URI.create("/assets/icons/documentation.svg")

    def createDocumentationLink(docUrl: String): ComponentLink =
      ComponentLink(DocumentationId, DocumentationTile, documentationIcon, URI.create(docUrl))
  }

  @JsonCodec
  final case class ComponentLink(id: String, title: String, icon: URI, url: URI)

  object ComponentListElement {
    def sortMethod(component: ComponentListElement): (String, String) = (component.name, component.id.value)
  }

  implicit val allowedProcessingModesEncoder: Encoder[AllowedProcessingModes] = Encoder.instance {
    case AllowedProcessingModes.AllProcessingModes            => ProcessingMode.all.asJson
    case AllowedProcessingModes.SetOf(allowedProcessingModes) => allowedProcessingModes.asJson
  }

  implicit val allowedProcessingModesDecoder: Decoder[AllowedProcessingModes] = Decoder.instance { c =>
    import ProcessingMode.processingModeOrdering
    c.as[SortedSet[ProcessingMode]]
      .map(NonEmptySet.fromSet)
      .flatMap {
        case None => Left(DecodingFailure("Set of allowed ProcessingModes cannot be empty", Nil))
        case Some(nonEmptySet) =>
          if (nonEmptySet.toSortedSet == ProcessingMode.all) {
            Right(AllowedProcessingModes.AllProcessingModes)
          } else {
            Right(AllowedProcessingModes.SetOf(nonEmptySet))
          }
      }
  }

  implicit val nonEmptySetOfProcessingModesSchema: Schema[NonEmptySet[ProcessingMode]] = Schema(
    SchemaType.SArray(Schema.schemaForString)(_.toSortedSet.toList.map(_.value))
  )

  @JsonCodec
  final case class ComponentListElement(
      id: DesignerWideComponentId,
      name: String,
      icon: String,
      componentType: ComponentType,
      componentGroupName: ComponentGroupName,
      categories: List[String],
      links: List[ComponentLink],
      usageCount: Long,
      allowedProcessingModes: AllowedProcessingModes
  ) {
    def componentId: ComponentId = ComponentId(componentType, name)
  }

  @JsonCodec
  final case class ComponentUsagesInScenario(
      name: ProcessName,
      nodesUsagesData: List[NodeUsageData],
      isFragment: Boolean,
      processCategory: String,
      modificationDate: Instant,
      modifiedAt: Instant,
      modifiedBy: String,
      createdAt: Instant,
      createdBy: String,
      lastAction: Option[ProcessAction]
  )

  implicit val uriSchema: Schema[URI] = Schema.string
}
