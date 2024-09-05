package pl.touk.nussknacker.ui.db.entity

import enumeratum.EnumEntry.UpperSnakecase
import enumeratum._
import io.circe.Decoder
import io.circe.syntax.EncoderOps
import pl.touk.nussknacker.engine.api.deployment.ProcessActionState.ProcessActionState
import pl.touk.nussknacker.engine.api.deployment._
import pl.touk.nussknacker.engine.api.process.ProcessId
import slick.lifted.{TableQuery => LTableQuery}
import slick.sql.SqlProfile.ColumnOption.NotNull

import java.sql.Timestamp
import java.util.UUID
import scala.collection.immutable

trait ScenarioActivityEntityFactory extends BaseEntityFactory {

  import profile.api._

  val scenarioActivityTable: LTableQuery[ScenarioActivityEntityFactory#ScenarioActivityEntity] = LTableQuery(
    new ScenarioActivityEntity(_)
  )

  class ScenarioActivityEntity(tag: Tag) extends Table[ScenarioActivityEntityData](tag, "scenario_activities") {

    def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)

    def activityType: Rep[ScenarioActivityType] = column[ScenarioActivityType]("activity_type", NotNull)

    def scenarioId: Rep[ProcessId] = column[ProcessId]("scenario_id", NotNull)

    def activityId: Rep[ScenarioActivityId] = column[ScenarioActivityId]("activity_id", NotNull)

    def userId: Rep[String] = column[String]("user_id", NotNull)

    def userName: Rep[String] = column[String]("user_name", NotNull)

    def impersonatedByUserId: Rep[Option[String]] = column[Option[String]]("impersonated_by_user_id")

    def impersonatedByUserName: Rep[Option[String]] = column[Option[String]]("impersonated_by_user_name")

    def lastModifiedByUserName: Rep[Option[String]] = column[Option[String]]("last_modified_by_user_name")

    def createdAt: Rep[Timestamp] = column[Timestamp]("created_at", NotNull)

    def scenarioVersion: Rep[Option[ScenarioVersion]] = column[Option[ScenarioVersion]]("scenario_version")

    def comment: Rep[Option[String]] = column[Option[String]]("comment")

    def attachmentId: Rep[Option[Long]] = column[Option[Long]]("attachment_id")

    def performedAt: Rep[Option[Timestamp]] = column[Option[Timestamp]]("performed_at")

    def state: Rep[Option[ProcessActionState]] = column[Option[ProcessActionState]]("state")

    def errorMessage: Rep[Option[String]] = column[Option[String]]("error_message")

    def buildInfo: Rep[Option[String]] = column[Option[String]]("build_info")

    def additionalProperties: Rep[AdditionalProperties] = column[AdditionalProperties]("additional_properties")

    override def * =
      (
        id,
        activityType,
        scenarioId,
        activityId,
        userId,
        userName,
        impersonatedByUserId,
        impersonatedByUserName,
        lastModifiedByUserName,
        createdAt,
        scenarioVersion,
        comment,
        attachmentId,
        performedAt,
        state,
        errorMessage,
        buildInfo,
        additionalProperties,
      ) <> (
        ScenarioActivityEntityData.apply _ tupled, ScenarioActivityEntityData.unapply
      )

  }

  implicit def scenarioActivityTypeMapper: BaseColumnType[ScenarioActivityType] =
    MappedColumnType.base[ScenarioActivityType, String](_.entryName, ScenarioActivityType.withName)

  implicit def scenarioIdMapper: BaseColumnType[ScenarioId] =
    MappedColumnType.base[ScenarioId, Long](_.value, ScenarioId.apply)

  implicit def scenarioActivityIdMapper: BaseColumnType[ScenarioActivityId] =
    MappedColumnType.base[ScenarioActivityId, UUID](_.value, ScenarioActivityId.apply)

  implicit def scenarioVersionMapper: BaseColumnType[ScenarioVersion] =
    MappedColumnType.base[ScenarioVersion, Long](_.value, ScenarioVersion.apply)

  implicit def additionalPropertiesMapper: BaseColumnType[AdditionalProperties] =
    MappedColumnType.base[AdditionalProperties, String](
      _.properties.asJson.noSpaces,
      jsonStr =>
        io.circe.parser.parse(jsonStr).flatMap(Decoder[Map[String, String]].decodeJson) match {
          case Right(rawParams) => AdditionalProperties(rawParams)
          case Left(error)      => throw error
        }
    )

}

sealed trait ScenarioActivityType extends EnumEntry with UpperSnakecase

object ScenarioActivityType extends Enum[ScenarioActivityType] {

  case object ScenarioCreated             extends ScenarioActivityType
  case object ScenarioArchived            extends ScenarioActivityType
  case object ScenarioUnarchived          extends ScenarioActivityType
  case object ScenarioDeployed            extends ScenarioActivityType
  case object ScenarioPaused              extends ScenarioActivityType
  case object ScenarioCanceled            extends ScenarioActivityType
  case object ScenarioModified            extends ScenarioActivityType
  case object ScenarioNameChanged         extends ScenarioActivityType
  case object CommentAdded                extends ScenarioActivityType
  case object AttachmentAdded             extends ScenarioActivityType
  case object ChangedProcessingMode       extends ScenarioActivityType
  case object IncomingMigration           extends ScenarioActivityType
  case object OutgoingMigration           extends ScenarioActivityType
  case object PerformedSingleExecution    extends ScenarioActivityType
  case object PerformedScheduledExecution extends ScenarioActivityType
  case object AutomaticUpdate             extends ScenarioActivityType

  override def values: immutable.IndexedSeq[ScenarioActivityType] = findValues

}

final case class AdditionalProperties(properties: Map[String, String])

object AdditionalProperties {
  def empty: AdditionalProperties = AdditionalProperties(Map.empty)
}

final case class ScenarioActivityEntityData(
    id: Long,
    activityType: ScenarioActivityType,       // actionName: ScenarioActionName
    scenarioId: ProcessId,                    // processId: ProcessId,
    activityId: ScenarioActivityId,           // id: ProcessActionId
    userId: String,                           // user: String,
    userName: String,                         // user: String,
    impersonatedByUserId: Option[String],     // impersonatedByIdentity: Option[String]
    impersonatedByUserName: Option[String],   // impersonatedByUsername: Option[String]
    lastModifiedByUserName: Option[String],   // user: String,
    createdAt: Timestamp,                     // createdAt: Timestamp,
    scenarioVersion: Option[ScenarioVersion], // processVersionId: Option[VersionId],

    comment: Option[String], // commentId: Option[Long],
    attachmentId: Option[Long],
    finishedAt: Option[Timestamp],     // performedAt: Option[Timestamp],
    state: Option[ProcessActionState], // state: ProcessActionState,
    errorMessage: Option[String],      // failureMessage: Option[String],
    buildInfo: Option[String],         // buildInfo: Option[String]

    additionalProperties: AdditionalProperties,
)
