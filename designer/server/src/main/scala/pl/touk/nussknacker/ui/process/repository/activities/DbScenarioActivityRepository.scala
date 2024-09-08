package pl.touk.nussknacker.ui.process.repository.activities

import cats.implicits.catsSyntaxEitherId
import com.typesafe.scalalogging.LazyLogging
import db.util.DBIOActionInstances.DB
import pl.touk.nussknacker.engine.api.component.ProcessingMode
import pl.touk.nussknacker.engine.api.deployment.ProcessActionState.ProcessActionState
import pl.touk.nussknacker.engine.api.deployment.ScenarioAttachment.{AttachmentFilename, AttachmentId}
import pl.touk.nussknacker.engine.api.deployment._
import pl.touk.nussknacker.engine.api.process.{ProcessId, VersionId}
import pl.touk.nussknacker.ui.api.description.scenarioActivity.Dtos.Legacy
import pl.touk.nussknacker.ui.db.entity.{
  AdditionalProperties,
  AttachmentEntityData,
  ScenarioActivityEntityData,
  ScenarioActivityType
}
import pl.touk.nussknacker.ui.db.{DbRef, NuTables}
import pl.touk.nussknacker.ui.process.ScenarioAttachmentService.AttachmentToAdd
import pl.touk.nussknacker.ui.process.repository.DbioRepository
import pl.touk.nussknacker.ui.process.repository.activities.ScenarioActivityRepository.ModifyCommentError
import pl.touk.nussknacker.ui.security.api.LoggedUser
import pl.touk.nussknacker.ui.statistics.{AttachmentsTotal, CommentsTotal}

import java.sql.Timestamp
import java.time.Instant
import scala.concurrent.ExecutionContext

class DbScenarioActivityRepository(override protected val dbRef: DbRef)(
    implicit executionContext: ExecutionContext,
) extends DbioRepository
    with NuTables
    with ScenarioActivityRepository
    with LazyLogging {

  import dbRef.profile.api._

  def findActivities(
      scenarioId: ProcessId,
  ): DB[Seq[ScenarioActivity]] = {
    doFindActivities(scenarioId).map(_.map(_._2))
  }

  private def doFindActivities(
      scenarioId: ProcessId,
  ): DB[Seq[(Long, ScenarioActivity)]] = {
    scenarioActivityTable
      .filter(_.scenarioId === scenarioId)
      .result
      .map(_.map(fromEntity))
      .map {
        _.flatMap {
          case Left(error) =>
            logger.warn(s"Ignoring invalid scenario activity: [$error]")
            None
          case Right(activity) =>
            Some(activity)
        }
      }
  }

  def addActivity(
      scenarioActivity: ScenarioActivity,
  )(implicit user: LoggedUser): DB[ScenarioActivityId] = {
    insertActivity(scenarioActivity).map(_.activityId)
  }

  def addComment(
      scenarioId: ProcessId,
      processVersionId: VersionId,
      comment: String,
  )(implicit user: LoggedUser): DB[ScenarioActivityId] = {
    insertActivity(
      ScenarioActivity.CommentAdded(
        scenarioId = ScenarioId(scenarioId.value),
        scenarioActivityId = ScenarioActivityId.random,
        user = toUser(user),
        date = Instant.now(),
        scenarioVersion = Some(ScenarioVersion(processVersionId.value)),
        comment = ScenarioComment.Available(
          comment = comment,
          lastModifiedByUserName = UserName(user.username),
        )
      ),
    ).map(_.activityId)
  }

  def editComment(
      scenarioId: ProcessId,
      rowId: Long,
      comment: String
  )(implicit user: LoggedUser): DB[Either[ModifyCommentError, Unit]] = {
    modifyActivityByRowId(
      rowId = rowId,
      activityDoesNotExistError = ModifyCommentError.ActivityDoesNotExist,
      validateCurrentValue = validateCommentExists(scenarioId),
      modify = _.copy(comment = Some(comment), lastModifiedByUserName = Some(user.username)),
      couldNotModifyError = ModifyCommentError.CouldNotModifyComment,
    )
  }

  def editComment(
      scenarioId: ProcessId,
      activityId: ScenarioActivityId,
      comment: String
  )(implicit user: LoggedUser): DB[Either[ModifyCommentError, Unit]] = {
    modifyActivityByActivityId(
      activityId = activityId,
      activityDoesNotExistError = ModifyCommentError.ActivityDoesNotExist,
      validateCurrentValue = validateCommentExists(scenarioId),
      modify = _.copy(comment = Some(comment), lastModifiedByUserName = Some(user.username)),
      couldNotModifyError = ModifyCommentError.CouldNotModifyComment,
    )
  }

  def deleteComment(
      scenarioId: ProcessId,
      rowId: Long,
  )(implicit user: LoggedUser): DB[Either[ModifyCommentError, Unit]] = {
    modifyActivityByRowId(
      rowId = rowId,
      activityDoesNotExistError = ModifyCommentError.ActivityDoesNotExist,
      validateCurrentValue = validateCommentExists(scenarioId),
      modify = _.copy(comment = None, lastModifiedByUserName = Some(user.username)),
      couldNotModifyError = ModifyCommentError.CouldNotModifyComment,
    )
  }

  def deleteComment(
      scenarioId: ProcessId,
      activityId: ScenarioActivityId,
  )(implicit user: LoggedUser): DB[Either[ModifyCommentError, Unit]] = {
    modifyActivityByActivityId(
      activityId = activityId,
      activityDoesNotExistError = ModifyCommentError.ActivityDoesNotExist,
      validateCurrentValue = validateCommentExists(scenarioId),
      modify = _.copy(comment = None, lastModifiedByUserName = Some(user.username)),
      couldNotModifyError = ModifyCommentError.CouldNotModifyComment,
    )
  }

  private def validateCommentExists(scenarioId: ProcessId)(entity: ScenarioActivityEntityData) = {
    for {
      _ <- Either.cond(entity.scenarioId == scenarioId, (), ModifyCommentError.CommentDoesNotExist)
      _ <- entity.comment.toRight(ModifyCommentError.CommentDoesNotExist)
    } yield ()
  }

  def addAttachment(
      attachmentToAdd: AttachmentToAdd
  )(implicit user: LoggedUser): DB[ScenarioActivityId] = {
    for {
      attachment <- attachmentInsertQuery += AttachmentEntityData(
        id = -1L,
        processId = attachmentToAdd.scenarioId,
        processVersionId = attachmentToAdd.scenarioVersionId,
        fileName = attachmentToAdd.fileName,
        data = attachmentToAdd.data,
        user = user.username,
        impersonatedByIdentity = user.impersonatingUserId,
        impersonatedByUsername = user.impersonatingUserName,
        createDate = Timestamp.from(Instant.now())
      )
      activity <- insertActivity(
        ScenarioActivity.AttachmentAdded(
          scenarioId = ScenarioId(attachmentToAdd.scenarioId.value),
          scenarioActivityId = ScenarioActivityId.random,
          user = toUser(user),
          date = Instant.now(),
          scenarioVersion = Some(ScenarioVersion(attachmentToAdd.scenarioVersionId.value)),
          attachment = ScenarioAttachment.Available(
            attachmentId = AttachmentId(attachment.id),
            attachmentFilename = AttachmentFilename(attachmentToAdd.fileName),
            lastModifiedByUserName = UserName(user.username),
          )
        ),
      )
    } yield activity.activityId
  }

  def findAttachments(
      scenarioId: ProcessId,
  ): DB[Seq[AttachmentEntityData]] = {
    attachmentsTable
      .filter(_.processId === scenarioId)
      .result
  }

  def findAttachment(
      scenarioId: ProcessId,
      attachmentId: Long,
  ): DB[Option[AttachmentEntityData]] = {
    attachmentsTable
      .filter(_.id === attachmentId)
      .filter(_.processId === scenarioId)
      .result
      .headOption
  }

  def findActivity(
      processId: ProcessId
  ): DB[Legacy.ProcessActivity] = {
    for {
      attachmentEntities <- findAttachments(processId)
      attachments = attachmentEntities.map(toDto)
      activities <- doFindActivities(processId)
      comments = activities.flatMap(toComment)
    } yield Legacy.ProcessActivity(
      comments = comments.toList,
      attachments = attachments.toList,
    )
  }

  private def toComment(
      id: Long,
      scenarioActivity: ScenarioActivity,
      comment: ScenarioComment,
      prefix: Option[String]
  ): Option[Legacy.Comment] = {
    for {
      scenarioVersion <- scenarioActivity.scenarioVersion
      content <- comment match {
        case ScenarioComment.Available(comment, _) => Some(comment)
        case ScenarioComment.Deleted(_)            => None
      }
    } yield Legacy.Comment(
      id = id,
      processVersionId = scenarioVersion.value,
      content = prefix.getOrElse("") + content,
      user = scenarioActivity.user.name.value,
      createDate = scenarioActivity.date,
    )
  }

  private def toComment(idAndActivity: (Long, ScenarioActivity)): Option[Legacy.Comment] = {
    val (id, scenarioActivity) = idAndActivity
    scenarioActivity match {
      case _: ScenarioActivity.ScenarioCreated =>
        None
      case _: ScenarioActivity.ScenarioArchived =>
        None
      case _: ScenarioActivity.ScenarioUnarchived =>
        None
      case activity: ScenarioActivity.ScenarioDeployed =>
        toComment(id, activity, activity.comment, Some("Deployment: "))
      case activity: ScenarioActivity.ScenarioPaused =>
        toComment(id, activity, activity.comment, Some("Pause: "))
      case activity: ScenarioActivity.ScenarioCanceled =>
        toComment(id, activity, activity.comment, Some("Stop: "))
      case activity: ScenarioActivity.ScenarioModified =>
        toComment(id, activity, activity.comment, None)
      case activity: ScenarioActivity.ScenarioNameChanged =>
        toComment(
          id,
          activity,
          ScenarioComment.Available(s"Rename: [${activity.oldName}] -> [${activity.newName}]", UserName("")),
          None
        )
      case activity: ScenarioActivity.CommentAdded =>
        toComment(id, activity, activity.comment, None)
      case _: ScenarioActivity.AttachmentAdded =>
        None
      case _: ScenarioActivity.ChangedProcessingMode =>
        None
      case _: ScenarioActivity.IncomingMigration =>
        None
      case _: ScenarioActivity.OutgoingMigration =>
        None
      case _: ScenarioActivity.PerformedSingleExecution =>
        None
      case _: ScenarioActivity.PerformedScheduledExecution =>
        None
      case _: ScenarioActivity.AutomaticUpdate =>
        None
      case _: ScenarioActivity.CustomAction =>
        None
    }
  }

  def getActivityStats: DB[Map[String, Int]] = {
    val activityTypesWithLegacyComments = Set(
      ScenarioActivityType.ScenarioDeployed,
      ScenarioActivityType.ScenarioPaused,
      ScenarioActivityType.ScenarioCanceled,
      ScenarioActivityType.ScenarioModified,
      ScenarioActivityType.ScenarioNameChanged,
      ScenarioActivityType.CommentAdded,
    )
    val findScenarioProcessActivityStats = for {
      attachmentsTotal <- attachmentsTable.length.result
      commentsTotal <- scenarioActivityTable.filter(_.activityType inSet activityTypesWithLegacyComments).length.result
    } yield Map(
      AttachmentsTotal -> attachmentsTotal,
      CommentsTotal    -> commentsTotal,
    ).map { case (k, v) => (k.toString, v) }
    run(findScenarioProcessActivityStats)
  }

  private def toUser(loggedUser: LoggedUser) = {
    User(
      id = UserId(loggedUser.id),
      name = UserName(loggedUser.username),
      impersonatedByUserId = loggedUser.impersonatingUserId.map(UserId.apply),
      impersonatedByUserName = loggedUser.impersonatingUserName.map(UserName.apply)
    )
  }

  private lazy val activityByRowIdCompiled = Compiled { rowId: Rep[Long] =>
    scenarioActivityTable.filter(_.id === rowId)
  }

  private lazy val activityByIdCompiled = Compiled { activityId: Rep[ScenarioActivityId] =>
    scenarioActivityTable.filter(_.activityId === activityId)
  }

  private lazy val attachmentInsertQuery =
    attachmentsTable returning attachmentsTable.map(_.id) into ((item, id) => item.copy(id = id))

  private def modifyActivityByActivityId[ERROR](
      activityId: ScenarioActivityId,
      activityDoesNotExistError: ERROR,
      validateCurrentValue: ScenarioActivityEntityData => Either[ERROR, Unit],
      modify: ScenarioActivityEntityData => ScenarioActivityEntityData,
      couldNotModifyError: ERROR,
  ): DB[Either[ERROR, Unit]] = {
    modifyActivity[ScenarioActivityId, ERROR](
      key = activityId,
      pullRows = activityByIdCompiled(_).result.headOption,
      updateRow = (id: ScenarioActivityId, updatedEntity) => activityByIdCompiled(id).update(updatedEntity),
      activityDoesNotExistError = activityDoesNotExistError,
      validateCurrentValue = validateCurrentValue,
      modify = modify,
      couldNotModifyError = couldNotModifyError
    )
  }

  private def modifyActivityByRowId[ERROR](
      rowId: Long,
      activityDoesNotExistError: ERROR,
      validateCurrentValue: ScenarioActivityEntityData => Either[ERROR, Unit],
      modify: ScenarioActivityEntityData => ScenarioActivityEntityData,
      couldNotModifyError: ERROR,
  ): DB[Either[ERROR, Unit]] = {
    modifyActivity[Long, ERROR](
      key = rowId,
      pullRows = activityByRowIdCompiled(_).result.headOption,
      updateRow = (id: Long, updatedEntity) => activityByRowIdCompiled(id).update(updatedEntity),
      activityDoesNotExistError = activityDoesNotExistError,
      validateCurrentValue = validateCurrentValue,
      modify = modify,
      couldNotModifyError = couldNotModifyError
    )
  }

  private def modifyActivity[KEY, ERROR](
      key: KEY,
      pullRows: KEY => DB[Option[ScenarioActivityEntityData]],
      updateRow: (KEY, ScenarioActivityEntityData) => DB[Int],
      activityDoesNotExistError: ERROR,
      validateCurrentValue: ScenarioActivityEntityData => Either[ERROR, Unit],
      modify: ScenarioActivityEntityData => ScenarioActivityEntityData,
      couldNotModifyError: ERROR,
  ): DB[Either[ERROR, Unit]] = {
    val action = for {
      dataPulled <- pullRows(key)
      result <- {
        val modifiedEntity = for {
          entity <- dataPulled.toRight(activityDoesNotExistError)
          _      <- validateCurrentValue(entity)
          modifiedEntity = modify(entity)
        } yield modifiedEntity

        modifiedEntity match {
          case Left(error) =>
            DBIO.successful(Left(error))
          case Right(modifiedEntity) =>
            for {
              rowsAffected <- updateRow(key, modifiedEntity)
              res          <- DBIO.successful(Either.cond(rowsAffected != 0, (), couldNotModifyError))
            } yield res
        }
      }
    } yield result
    action.transactionally
  }

  private def insertActivity(
      activity: ScenarioActivity,
  ): DB[ScenarioActivityEntityData] = {
    val entity = toEntity(activity)
    (scenarioActivityTable += entity).map { insertCount =>
      if (insertCount == 1) {
        entity
      } else {
        throw new RuntimeException(s"Unable to insert activity")
      }
    }
  }

  private def createEntity(scenarioActivity: ScenarioActivity)(
      attachmentId: Option[Long] = None,
      comment: Option[String] = None,
      lastModifiedByUserName: Option[String] = None,
      finishedAt: Option[Timestamp] = None,
      state: Option[ProcessActionState] = None,
      errorMessage: Option[String] = None,
      buildInfo: Option[String] = None,
      additionalProperties: AdditionalProperties = AdditionalProperties.empty,
  ): ScenarioActivityEntityData = {
    val activityType = scenarioActivity match {
      case _: ScenarioActivity.ScenarioCreated             => ScenarioActivityType.ScenarioCreated
      case _: ScenarioActivity.ScenarioArchived            => ScenarioActivityType.ScenarioArchived
      case _: ScenarioActivity.ScenarioUnarchived          => ScenarioActivityType.ScenarioUnarchived
      case _: ScenarioActivity.ScenarioDeployed            => ScenarioActivityType.ScenarioDeployed
      case _: ScenarioActivity.ScenarioPaused              => ScenarioActivityType.ScenarioPaused
      case _: ScenarioActivity.ScenarioCanceled            => ScenarioActivityType.ScenarioCanceled
      case _: ScenarioActivity.ScenarioModified            => ScenarioActivityType.ScenarioModified
      case _: ScenarioActivity.ScenarioNameChanged         => ScenarioActivityType.ScenarioNameChanged
      case _: ScenarioActivity.CommentAdded                => ScenarioActivityType.CommentAdded
      case _: ScenarioActivity.AttachmentAdded             => ScenarioActivityType.AttachmentAdded
      case _: ScenarioActivity.ChangedProcessingMode       => ScenarioActivityType.ChangedProcessingMode
      case _: ScenarioActivity.IncomingMigration           => ScenarioActivityType.IncomingMigration
      case _: ScenarioActivity.OutgoingMigration           => ScenarioActivityType.OutgoingMigration
      case _: ScenarioActivity.PerformedSingleExecution    => ScenarioActivityType.PerformedSingleExecution
      case _: ScenarioActivity.PerformedScheduledExecution => ScenarioActivityType.PerformedScheduledExecution
      case _: ScenarioActivity.AutomaticUpdate             => ScenarioActivityType.AutomaticUpdate
      case activity: ScenarioActivity.CustomAction         => ScenarioActivityType.CustomAction(activity.actionName)
    }
    ScenarioActivityEntityData(
      id = -1,
      activityType = activityType,
      scenarioId = ProcessId(scenarioActivity.scenarioId.value),
      activityId = ScenarioActivityId.random,
      userId = scenarioActivity.user.id.value,
      userName = scenarioActivity.user.name.value,
      impersonatedByUserId = scenarioActivity.user.impersonatedByUserId.map(_.value),
      impersonatedByUserName = scenarioActivity.user.impersonatedByUserName.map(_.value),
      lastModifiedByUserName = lastModifiedByUserName,
      createdAt = Timestamp.from(Instant.now()),
      scenarioVersion = scenarioActivity.scenarioVersion,
      comment = comment,
      attachmentId = attachmentId,
      finishedAt = finishedAt,
      state = state,
      errorMessage = errorMessage,
      buildInfo = buildInfo,
      additionalProperties = additionalProperties,
    )
  }

  private def comment(scenarioComment: ScenarioComment): Option[String] = {
    scenarioComment match {
      case ScenarioComment.Available(comment, _) => Some(comment.value)
      case ScenarioComment.Deleted(_)            => None
    }
  }

  private def lastModifiedByUserName(scenarioComment: ScenarioComment): Option[String] = {
    val userName = scenarioComment match {
      case ScenarioComment.Available(_, lastModifiedByUserName) => lastModifiedByUserName
      case ScenarioComment.Deleted(deletedByUserName)           => deletedByUserName
    }
    Some(userName.value)
  }

  private def lastModifiedByUserName(scenarioAttachment: ScenarioAttachment): Option[String] = {
    val userName = scenarioAttachment match {
      case ScenarioAttachment.Available(_, _, lastModifiedByUserName) =>
        Some(lastModifiedByUserName.value)
      case ScenarioAttachment.Deleted(_, deletedByUserName) =>
        Some(deletedByUserName.value)
    }
    Some(userName.value)
  }

  def toEntity(scenarioActivity: ScenarioActivity): ScenarioActivityEntityData = {
    scenarioActivity match {
      case _: ScenarioActivity.ScenarioCreated =>
        createEntity(scenarioActivity)()
      case _: ScenarioActivity.ScenarioArchived =>
        createEntity(scenarioActivity)()
      case _: ScenarioActivity.ScenarioUnarchived =>
        createEntity(scenarioActivity)()
      case activity: ScenarioActivity.ScenarioDeployed =>
        createEntity(scenarioActivity)(
          comment = comment(activity.comment),
          lastModifiedByUserName = lastModifiedByUserName(activity.comment),
        )
      case activity: ScenarioActivity.ScenarioPaused =>
        createEntity(scenarioActivity)(
          comment = comment(activity.comment),
          lastModifiedByUserName = lastModifiedByUserName(activity.comment),
        )
      case activity: ScenarioActivity.ScenarioCanceled =>
        createEntity(scenarioActivity)(
          comment = comment(activity.comment),
          lastModifiedByUserName = lastModifiedByUserName(activity.comment),
        )
      case activity: ScenarioActivity.ScenarioModified =>
        createEntity(scenarioActivity)(
          comment = comment(activity.comment),
          lastModifiedByUserName = lastModifiedByUserName(activity.comment),
        )
      case activity: ScenarioActivity.ScenarioNameChanged =>
        createEntity(scenarioActivity)(
          additionalProperties = AdditionalProperties(
            Map(
              "oldName" -> activity.oldName,
              "newName" -> activity.newName,
            )
          )
        )
      case activity: ScenarioActivity.CommentAdded =>
        createEntity(scenarioActivity)(
          comment = comment(activity.comment),
          lastModifiedByUserName = lastModifiedByUserName(activity.comment),
          additionalProperties = AdditionalProperties.empty,
        )
      case activity: ScenarioActivity.AttachmentAdded =>
        val (attachmentId, attachmentFilename) = activity.attachment match {
          case ScenarioAttachment.Available(id, filename, _) => (Some(id.value), Some(filename.value))
          case ScenarioAttachment.Deleted(filename, _)       => (None, Some(filename.value))
        }
        createEntity(scenarioActivity)(
          attachmentId = attachmentId,
          lastModifiedByUserName = lastModifiedByUserName(activity.attachment),
          additionalProperties = AdditionalProperties(
            attachmentFilename.map("attachmentFilename" -> _).toMap
          )
        )
      case activity: ScenarioActivity.ChangedProcessingMode =>
        createEntity(scenarioActivity)(
          additionalProperties = AdditionalProperties(
            Map(
              "fromProcessingMode" -> activity.from.entryName,
              "toProcessingMode"   -> activity.to.entryName,
            )
          )
        )
      case activity: ScenarioActivity.IncomingMigration =>
        createEntity(scenarioActivity)(
          additionalProperties = AdditionalProperties(
            Map(
              "sourceEnvironment"     -> activity.sourceEnvironment.name,
              "sourceScenarioVersion" -> activity.sourceScenarioVersion.value.toString,
            )
          )
        )
      case activity: ScenarioActivity.OutgoingMigration =>
        createEntity(scenarioActivity)(
          comment = comment(activity.comment),
          lastModifiedByUserName = lastModifiedByUserName(activity.comment),
          additionalProperties = AdditionalProperties(
            Map(
              "destinationEnvironment" -> activity.destinationEnvironment.name,
            )
          )
        )
      case activity: ScenarioActivity.PerformedSingleExecution =>
        createEntity(scenarioActivity)(
          finishedAt = Some(Timestamp.from(activity.dateFinished)),
          errorMessage = activity.errorMessage,
        )
      case activity: ScenarioActivity.PerformedScheduledExecution =>
        createEntity(scenarioActivity)(
          finishedAt = Some(Timestamp.from(activity.dateFinished)),
          errorMessage = activity.errorMessage,
        )
      case activity: ScenarioActivity.AutomaticUpdate =>
        createEntity(scenarioActivity)(
          finishedAt = Some(Timestamp.from(activity.dateFinished)),
          errorMessage = activity.errorMessage,
          additionalProperties = AdditionalProperties(
            Map(
              "description" -> activity.changes.mkString(",\n"),
            )
          )
        )
      case _: ScenarioActivity.CustomAction =>
        createEntity(scenarioActivity)()
    }
  }

  private def userFromEntity(entity: ScenarioActivityEntityData): User = {
    User(
      id = UserId(entity.userId),
      name = UserName(entity.userName),
      impersonatedByUserId = entity.impersonatedByUserId.map(UserId.apply),
      impersonatedByUserName = entity.impersonatedByUserName.map(UserName.apply),
    )
  }

  private def scenarioIdFromEntity(entity: ScenarioActivityEntityData): ScenarioId = {
    ScenarioId(entity.scenarioId.value)
  }

  private def commentFromEntity(entity: ScenarioActivityEntityData): Either[String, ScenarioComment] = {
    for {
      lastModifiedByUserName <- entity.lastModifiedByUserName.toRight("Missing lastModifiedByUserName field")
    } yield {
      entity.comment match {
        case Some(comment) =>
          ScenarioComment.Available(comment = comment, lastModifiedByUserName = UserName(lastModifiedByUserName))
        case None =>
          ScenarioComment.Deleted(deletedByUserName = UserName(lastModifiedByUserName))
      }
    }
  }

  private def attachmentFromEntity(entity: ScenarioActivityEntityData): Either[String, ScenarioAttachment] = {
    for {
      lastModifiedByUserName <- entity.lastModifiedByUserName.toRight("Missing lastModifiedByUserName field")
      filename               <- additionalPropertyFromEntity(entity, "attachmentFilename")
    } yield {
      entity.attachmentId match {
        case Some(id) =>
          ScenarioAttachment.Available(
            attachmentId = AttachmentId(id),
            attachmentFilename = AttachmentFilename(filename),
            lastModifiedByUserName = UserName(lastModifiedByUserName)
          )
        case None =>
          ScenarioAttachment.Deleted(
            attachmentFilename = AttachmentFilename(filename),
            deletedByUserName = UserName(lastModifiedByUserName)
          )
      }
    }
  }

  private def additionalPropertyFromEntity(entity: ScenarioActivityEntityData, name: String): Either[String, String] = {
    entity.additionalProperties.properties.get(name).toRight(s"Missing additional property $name")
  }

  def fromEntity(entity: ScenarioActivityEntityData): Either[String, (Long, ScenarioActivity)] = {
    entity.activityType match {
      case ScenarioActivityType.ScenarioCreated =>
        ScenarioActivity
          .ScenarioCreated(
            scenarioId = scenarioIdFromEntity(entity),
            scenarioActivityId = entity.activityId,
            user = userFromEntity(entity),
            date = entity.createdAt.toInstant,
            scenarioVersion = entity.scenarioVersion
          )
          .asRight
          .map((entity.id, _))
      case ScenarioActivityType.ScenarioArchived =>
        ScenarioActivity
          .ScenarioArchived(
            scenarioId = scenarioIdFromEntity(entity),
            scenarioActivityId = entity.activityId,
            user = userFromEntity(entity),
            date = entity.createdAt.toInstant,
            scenarioVersion = entity.scenarioVersion
          )
          .asRight
          .map((entity.id, _))
      case ScenarioActivityType.ScenarioUnarchived =>
        ScenarioActivity
          .ScenarioUnarchived(
            scenarioId = scenarioIdFromEntity(entity),
            scenarioActivityId = entity.activityId,
            user = userFromEntity(entity),
            date = entity.createdAt.toInstant,
            scenarioVersion = entity.scenarioVersion
          )
          .asRight
          .map((entity.id, _))
      case ScenarioActivityType.ScenarioDeployed =>
        commentFromEntity(entity)
          .map { comment =>
            ScenarioActivity.ScenarioDeployed(
              scenarioId = scenarioIdFromEntity(entity),
              scenarioActivityId = entity.activityId,
              user = userFromEntity(entity),
              date = entity.createdAt.toInstant,
              scenarioVersion = entity.scenarioVersion,
              comment = comment,
            )
          }
          .map((entity.id, _))
      case ScenarioActivityType.ScenarioPaused =>
        commentFromEntity(entity)
          .map { comment =>
            ScenarioActivity.ScenarioPaused(
              scenarioId = scenarioIdFromEntity(entity),
              scenarioActivityId = entity.activityId,
              user = userFromEntity(entity),
              date = entity.createdAt.toInstant,
              scenarioVersion = entity.scenarioVersion,
              comment = comment,
            )
          }
          .map((entity.id, _))
      case ScenarioActivityType.ScenarioCanceled =>
        commentFromEntity(entity)
          .map { comment =>
            ScenarioActivity.ScenarioCanceled(
              scenarioId = scenarioIdFromEntity(entity),
              scenarioActivityId = entity.activityId,
              user = userFromEntity(entity),
              date = entity.createdAt.toInstant,
              scenarioVersion = entity.scenarioVersion,
              comment = comment,
            )
          }
          .map((entity.id, _))
      case ScenarioActivityType.ScenarioModified =>
        commentFromEntity(entity)
          .map { comment =>
            ScenarioActivity.ScenarioModified(
              scenarioId = scenarioIdFromEntity(entity),
              scenarioActivityId = entity.activityId,
              user = userFromEntity(entity),
              date = entity.createdAt.toInstant,
              scenarioVersion = entity.scenarioVersion,
              comment = comment,
            )
          }
          .map((entity.id, _))
      case ScenarioActivityType.ScenarioNameChanged =>
        (for {
          oldName <- additionalPropertyFromEntity(entity, "oldName")
          newName <- additionalPropertyFromEntity(entity, "newName")
        } yield ScenarioActivity.ScenarioNameChanged(
          scenarioId = scenarioIdFromEntity(entity),
          scenarioActivityId = entity.activityId,
          user = userFromEntity(entity),
          date = entity.createdAt.toInstant,
          scenarioVersion = entity.scenarioVersion,
          oldName = oldName,
          newName = newName
        )).map((entity.id, _))
      case ScenarioActivityType.CommentAdded =>
        (for {
          comment <- commentFromEntity(entity)
        } yield ScenarioActivity.CommentAdded(
          scenarioId = scenarioIdFromEntity(entity),
          scenarioActivityId = entity.activityId,
          user = userFromEntity(entity),
          date = entity.createdAt.toInstant,
          scenarioVersion = entity.scenarioVersion,
          comment = comment,
        )).map((entity.id, _))
      case ScenarioActivityType.AttachmentAdded =>
        (for {
          attachment <- attachmentFromEntity(entity)
        } yield ScenarioActivity.AttachmentAdded(
          scenarioId = scenarioIdFromEntity(entity),
          scenarioActivityId = entity.activityId,
          user = userFromEntity(entity),
          date = entity.createdAt.toInstant,
          scenarioVersion = entity.scenarioVersion,
          attachment = attachment,
        )).map((entity.id, _))
      case ScenarioActivityType.ChangedProcessingMode =>
        (for {
          from <- additionalPropertyFromEntity(entity, "fromProcessingMode").flatMap(
            ProcessingMode.withNameEither(_).left.map(_.getMessage())
          )
          to <- additionalPropertyFromEntity(entity, "toProcessingMode").flatMap(
            ProcessingMode.withNameEither(_).left.map(_.getMessage())
          )
        } yield ScenarioActivity.ChangedProcessingMode(
          scenarioId = scenarioIdFromEntity(entity),
          scenarioActivityId = entity.activityId,
          user = userFromEntity(entity),
          date = entity.createdAt.toInstant,
          scenarioVersion = entity.scenarioVersion,
          from = from,
          to = to,
        )).map((entity.id, _))
      case ScenarioActivityType.IncomingMigration =>
        (for {
          sourceEnvironment <- additionalPropertyFromEntity(entity, "sourceEnvironment")
          sourceScenarioVersion <- additionalPropertyFromEntity(entity, "sourceScenarioVersion").flatMap(
            _.toLongOption.toRight("sourceScenarioVersion is not a valid Long")
          )
        } yield ScenarioActivity.IncomingMigration(
          scenarioId = scenarioIdFromEntity(entity),
          scenarioActivityId = entity.activityId,
          user = userFromEntity(entity),
          date = entity.createdAt.toInstant,
          scenarioVersion = entity.scenarioVersion,
          sourceEnvironment = Environment(sourceEnvironment),
          sourceScenarioVersion = ScenarioVersion(sourceScenarioVersion)
        )).map((entity.id, _))
      case ScenarioActivityType.OutgoingMigration =>
        (for {
          comment                <- commentFromEntity(entity)
          destinationEnvironment <- additionalPropertyFromEntity(entity, "destinationEnvironment")
        } yield ScenarioActivity.OutgoingMigration(
          scenarioId = scenarioIdFromEntity(entity),
          scenarioActivityId = entity.activityId,
          user = userFromEntity(entity),
          date = entity.createdAt.toInstant,
          scenarioVersion = entity.scenarioVersion,
          comment = comment,
          destinationEnvironment = Environment(destinationEnvironment),
        )).map((entity.id, _))
      case ScenarioActivityType.PerformedSingleExecution =>
        (for {
          finishedAt <- entity.finishedAt.map(_.toInstant).toRight("Missing finishedAt")
        } yield ScenarioActivity.PerformedSingleExecution(
          scenarioId = scenarioIdFromEntity(entity),
          scenarioActivityId = entity.activityId,
          user = userFromEntity(entity),
          date = entity.createdAt.toInstant,
          scenarioVersion = entity.scenarioVersion,
          dateFinished = finishedAt,
          errorMessage = entity.errorMessage,
        )).map((entity.id, _))
      case ScenarioActivityType.PerformedScheduledExecution =>
        (for {
          finishedAt <- entity.finishedAt.map(_.toInstant).toRight("Missing finishedAt")
        } yield ScenarioActivity.PerformedScheduledExecution(
          scenarioId = scenarioIdFromEntity(entity),
          scenarioActivityId = entity.activityId,
          user = userFromEntity(entity),
          date = entity.createdAt.toInstant,
          scenarioVersion = entity.scenarioVersion,
          dateFinished = finishedAt,
          errorMessage = entity.errorMessage,
        )).map((entity.id, _))
      case ScenarioActivityType.AutomaticUpdate =>
        (for {
          finishedAt  <- entity.finishedAt.map(_.toInstant).toRight("Missing finishedAt")
          description <- additionalPropertyFromEntity(entity, "description")
        } yield ScenarioActivity.AutomaticUpdate(
          scenarioId = scenarioIdFromEntity(entity),
          scenarioActivityId = entity.activityId,
          user = userFromEntity(entity),
          date = entity.createdAt.toInstant,
          scenarioVersion = entity.scenarioVersion,
          dateFinished = finishedAt,
          errorMessage = entity.errorMessage,
          changes = description,
        )).map((entity.id, _))

      case ScenarioActivityType.CustomAction(actionName) =>
        ScenarioActivity
          .CustomAction(
            scenarioId = scenarioIdFromEntity(entity),
            scenarioActivityId = entity.activityId,
            user = userFromEntity(entity),
            date = entity.createdAt.toInstant,
            scenarioVersion = entity.scenarioVersion,
            actionName = actionName,
          )
          .asRight
          .map((entity.id, _))
    }
  }

  private def toDto(attachmentEntityData: AttachmentEntityData): Legacy.Attachment = {
    Legacy.Attachment(
      id = attachmentEntityData.id,
      processVersionId = attachmentEntityData.processVersionId.value,
      fileName = attachmentEntityData.fileName,
      user = attachmentEntityData.user,
      createDate = attachmentEntityData.createDateTime,
    )
  }

}
