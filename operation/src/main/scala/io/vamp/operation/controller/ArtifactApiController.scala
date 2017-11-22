package io.vamp.operation.controller

import java.net.URLDecoder

import akka.pattern.ask
import akka.util.Timeout
import io.vamp.common.akka.IoC._
import io.vamp.common.{ Artifact, Namespace }
import io.vamp.model.artifact._
import io.vamp.model.notification.{ ImportReferenceError, InconsistentArtifactName }
import io.vamp.model.reader.{ YamlReader, _ }
import io.vamp.model.serialization.CoreSerializationFormat
import io.vamp.operation.notification.UnexpectedArtifact
import io.vamp.persistence.notification.PersistenceOperationFailure
import io.vamp.persistence.{ ArtifactExpansionSupport, ArtifactResponseEnvelope, PersistenceActor }
import org.json4s.native.Serialization.write
import org.json4s.{ DefaultFormats, Extraction }

import scala.concurrent.Future

trait ArtifactApiController
    extends DeploymentApiController
    with GatewayApiController
    with WorkflowApiController
    with MultipleArtifactApiController
    with SingleArtifactApiController
    with ArtifactExpansionSupport
    with AbstractController {

  def background(artifact: String)(implicit namespace: Namespace): Boolean = !crud(artifact)

  def readArtifacts(kind: String, expandReferences: Boolean, onlyReferences: Boolean)(page: Int, perPage: Int)(implicit namespace: Namespace, timeout: Timeout): Future[ArtifactResponseEnvelope] = `type`(kind) match {
    case (t, _) if t == classOf[Deployment] ⇒ Future.successful(ArtifactResponseEnvelope(Nil, 0, 1, ArtifactResponseEnvelope.maxPerPage))
    case (t, _) ⇒
      actorFor[PersistenceActor] ? PersistenceActor.All(t, page, perPage, expandReferences, onlyReferences) map {
        case envelope: ArtifactResponseEnvelope ⇒ envelope
        case other                              ⇒ throwException(PersistenceOperationFailure(other))
      }
  }
}

trait SingleArtifactApiController extends SourceTransformer with AbstractController {
  this: ArtifactApiController ⇒

  def createArtifact(kind: String, source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = `type`(kind) match {
    case (t, _) if t == classOf[Deployment] ⇒ throwException(UnexpectedArtifact(kind))
    case (t, r) if t == classOf[Gateway]    ⇒ unmarshall(r, source).flatMap(createGateway(_, source, validateOnly))
    case (t, r) if t == classOf[Workflow]   ⇒ unmarshall(r, source).flatMap(createWorkflow(_, validateOnly))
    case (_, r)                             ⇒ unmarshall(r, source).flatMap(create(_, source, validateOnly))
  }

  def readArtifact(kind: String, name: String, expandReferences: Boolean, onlyReferences: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = `type`(kind) match {
    case (t, _) if t == classOf[Gateway]    ⇒ actorFor[PersistenceActor] ? PersistenceActor.Read(URLDecoder.decode(name, "UTF-8"), t, expandReferences, onlyReferences)
    case (t, _) if t == classOf[Deployment] ⇒ Future.successful(None)
    case (t, _)                             ⇒ read(t, name, expandReferences, onlyReferences)
  }

  def updateArtifact(kind: String, name: String, source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = `type`(kind) match {
    case (t, _) if t == classOf[Deployment] ⇒ throwException(UnexpectedArtifact(kind))
    case (t, r) if t == classOf[Gateway]    ⇒ unmarshall(r, source).flatMap(updateGateway(_, name, source, validateOnly))
    case (t, r) if t == classOf[Workflow]   ⇒ unmarshall(r, source).flatMap(updateWorkflow(_, name, validateOnly))
    case (_, r)                             ⇒ unmarshall(r, source).flatMap(update(_, name, source, validateOnly))
  }

  def deleteArtifact(kind: String, name: String, source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = `type`(kind) match {
    case (t, _) if t == classOf[Deployment] ⇒ Future.successful(None)
    case (t, _) if t == classOf[Gateway]    ⇒ deleteGateway(name, validateOnly)
    case (t, _) if t == classOf[Workflow]   ⇒ deleteWorkflow(read(t, name, expandReferences = false, onlyReferences = false), validateOnly)
    case (t, _)                             ⇒ delete(t, name, validateOnly)
  }

  protected def crud(kind: String)(implicit namespace: Namespace): Boolean = `type`(kind) match {
    case (t, _) if t == classOf[Gateway]    ⇒ false
    case (t, _) if t == classOf[Deployment] ⇒ false
    case (t, _) if t == classOf[Workflow]   ⇒ false
    case _                                  ⇒ true
  }

  private def read(`type`: Class[_ <: Artifact], name: String, expandReferences: Boolean, onlyReferences: Boolean)(implicit namespace: Namespace, timeout: Timeout) = {
    actorFor[PersistenceActor] ? PersistenceActor.Read(name, `type`, expandReferences, onlyReferences)
  }

  private def create(artifact: Artifact, source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout) = {
    if (validateOnly) Future.successful(artifact) else actorFor[PersistenceActor] ? PersistenceActor.Create(artifact, Option(source))
  }

  private def update(artifact: Artifact, name: String, source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout) = {
    if (name != artifact.name)
      throwException(InconsistentArtifactName(name, artifact.name))

    if (validateOnly) Future.successful(artifact) else actorFor[PersistenceActor] ? PersistenceActor.Update(artifact, Some(source))
  }

  private def delete(`type`: Class[_ <: Artifact], name: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout) = {
    if (validateOnly) Future.successful(None) else actorFor[PersistenceActor] ? PersistenceActor.Delete(name, `type`)
  }

  private def unmarshall(reader: YamlReader[_ <: Artifact], source: String)(implicit namespace: Namespace, timeout: Timeout): Future[Artifact] = {
    sourceImport(source).map(reader.read(_))
  }
}

trait MultipleArtifactApiController extends AbstractController {
  this: SingleArtifactApiController with DeploymentApiController ⇒

  def createArtifacts(source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = process(source, {
    item ⇒
      `type`(item.kind) match {
        case (t, _) if t == classOf[Deployment] ⇒ createDeployment(item.toString, validateOnly)
        case _                                  ⇒ createArtifact(item.kind, item.toString, validateOnly)
      }
  })

  def updateArtifacts(source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = process(source, {
    item ⇒
      `type`(item.kind) match {
        case (t, _) if t == classOf[Deployment] ⇒ updateDeployment(item.name, item.toString, validateOnly)
        case _                                  ⇒ updateArtifact(item.kind, item.name, item.toString, validateOnly)
      }
  })

  def deleteArtifacts(source: String, validateOnly: Boolean)(implicit namespace: Namespace, timeout: Timeout): Future[Any] = process(source, {
    item ⇒
      `type`(item.kind) match {
        case (t, _) if t == classOf[Deployment] ⇒ deleteDeployment(item.name, item.toString, validateOnly)
        case _                                  ⇒ deleteArtifact(item.kind, item.name, item.toString, validateOnly)
      }
  })

  private def process(source: String, execute: ArtifactSource ⇒ Future[Any]) = Future.sequence {
    ArtifactListReader.read(source).map(execute)
  }
}

trait SourceTransformer {
  this: AbstractController ⇒

  def sourceImport(source: String)(implicit namespace: Namespace, timeout: Timeout): Future[String] = {
    val artifact = ImportReader.read(source)
    Future.sequence(artifact.references.map { ref ⇒
      val (kind, _) = `type`(ref.kind)
      (actorFor[PersistenceActor] ? PersistenceActor.Read(ref.name, kind, expandReferences = true)).map(r ⇒ ref → r)
    }).map { imports ⇒
      val decomposed = imports.map {
        case (_, Some(t: Template)) ⇒ Extraction.decompose(t.definition)(DefaultFormats)
        case (_, Some(other))       ⇒ Extraction.decompose(other)(CoreSerializationFormat.default)
        case (r, _)                 ⇒ throwException(ImportReferenceError(r.toString))
      }
      val expanded = {
        if (decomposed.isEmpty)
          Extraction.decompose(artifact.base)(DefaultFormats)
        else
          decomposed.reduceLeft { (a, b) ⇒ a merge b }.merge(Extraction.decompose(artifact.base)(DefaultFormats))
      }
      write(expanded)(DefaultFormats)
    }
  }

  protected def `type`(kind: String)(implicit namespace: Namespace): (Class[_ <: Artifact], YamlReader[_ <: Artifact]) = kind match {
    case Breed.kind      ⇒ (classOf[Breed], BreedReader)
    case Blueprint.kind  ⇒ (classOf[Blueprint], BlueprintReader)
    case Sla.kind        ⇒ (classOf[Sla], SlaReader)
    case Scale.kind      ⇒ (classOf[Scale], ScaleReader)
    case Escalation.kind ⇒ (classOf[Escalation], EscalationReader)
    case Route.kind      ⇒ (classOf[Route], RouteReader)
    case Condition.kind  ⇒ (classOf[Condition], ConditionReader)
    case Rewrite.kind    ⇒ (classOf[Rewrite], RewriteReader)
    case Workflow.kind   ⇒ (classOf[Workflow], WorkflowReader)
    case Gateway.kind    ⇒ (classOf[Gateway], GatewayReader)
    case Deployment.kind ⇒ (classOf[Deployment], DeploymentReader)
    case Template.kind   ⇒ (classOf[Template], TemplateReader)
    case _               ⇒ throwException(UnexpectedArtifact(kind))
  }
}
