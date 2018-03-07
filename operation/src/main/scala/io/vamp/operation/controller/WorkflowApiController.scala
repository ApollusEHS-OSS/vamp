package io.vamp.operation.controller

import akka.pattern.ask
import akka.util.Timeout
import io.vamp.common.{ Artifact, Namespace }
import io.vamp.common.akka.IoC.actorFor
import io.vamp.model.artifact._
import io.vamp.model.notification.InconsistentArtifactName
import io.vamp.model.reader.WorkflowStatusReader
import io.vamp.operation.notification.{ DeploymentWorkflowNameCollision, WorkflowUpdateError }
import io.vamp.persistence.{ ArtifactExpansionSupport, PersistenceActor }

import scala.concurrent.Future

trait WorkflowApiController extends AbstractController {
  this: ArtifactExpansionSupport ⇒

  import PersistenceActor._

  protected def createWorkflow(artifact: Artifact, source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = {
    updateWorkflow(artifact, source, validateOnly, create = true)
  }

  protected def updateWorkflow(artifact: Artifact, name: String, source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = {
    if (name != artifact.name)
      throwException(InconsistentArtifactName(name, artifact.name))
    updateWorkflow(artifact, source, validateOnly, create = false)
  }

  private def updateWorkflow(artifact: Artifact, source: String, validateOnly: Boolean, create: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = {
    artifactForIfExists[Deployment](artifact.name).flatMap {
      case Some(_) ⇒ throwException(DeploymentWorkflowNameCollision(artifact.name))
      case _ ⇒ artifactForIfExists[Workflow](artifact.name).flatMap {
        case Some(workflow) if workflow.status != Workflow.Status.Suspended ⇒ throwException(WorkflowUpdateError(workflow))
        case _ ⇒
          if (validateOnly)
            Future.successful(artifact)
          else
            actorFor[PersistenceActor] ? (if (create) PersistenceActor.Create(artifact, Option(source)) else PersistenceActor.Update(artifact, Some(source)))
      }
    } map {
      case list: List[_] ⇒
        if (!validateOnly) list.foreach { case workflow: Workflow ⇒ actorFor[PersistenceActor] ? ResetWorkflow(workflow) }
        list
      case any ⇒ any
    }
  }

  protected def deleteWorkflow(read: Future[Any], source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = {
    read.flatMap {
      case Some(workflow: Workflow) ⇒
        if (validateOnly) Future.successful(true) else actorFor[PersistenceActor] ? UpdateWorkflowStatus(workflow, Workflow.Status.Stopping)
      case _ ⇒ Future.successful(false)
    }
  }

  protected def workflowStatus(name: String)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = {
    (actorFor[PersistenceActor] ? PersistenceActor.Read(name, classOf[Workflow])).map {
      case Some(workflow: Workflow) ⇒ workflow.status.toString
      case _                        ⇒ None
    }
  }

  def workflowStatusUpdate(name: String, request: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = {
    val status = WorkflowStatusReader.status(request)
    if (validateOnly) Future.successful(status.toString)
    else actorFor[PersistenceActor] ? PersistenceActor.Read(name, classOf[Workflow]) flatMap {
      case Some(workflow: Workflow) ⇒ actorFor[PersistenceActor] ? UpdateWorkflowStatus(workflow, status)
      case _                        ⇒ Future(None)
    }
  }
}
