package io.vamp.persistence

import akka.actor.Actor
import io.vamp.common.Artifact
import io.vamp.common.http.OffsetEnvelope

import scala.collection.mutable
import scala.concurrent.Future
import scala.language.postfixOps
import scala.reflect.{ ClassTag, classTag }

trait InMemoryRepresentationPersistenceActor extends PersistenceActor with TypeOfArtifact {

  private var records = 0

  private val store: mutable.Map[String, mutable.Map[String, Artifact]] = new mutable.HashMap()

  protected var validData = true

  override def receive: Actor.Receive = query orElse super[PersistenceActor].receive

  protected def query: Actor.Receive = PartialFunction.empty

  protected def all(`type`: Class[_ <: Artifact], page: Int, perPage: Int, filter: (Artifact) ⇒ Boolean = (_) ⇒ true): Future[ArtifactResponseEnvelope] = {
    Future.successful(allArtifacts(`type`, page, perPage, filter))
  }

  protected def get(name: String, `type`: Class[_ <: Artifact]): Future[Option[Artifact]] = Future.successful(readArtifact(name, `type`))

  protected def info(): Future[Map[String, Any]] = Future.successful(Map[String, Any](
    "status" → (if (validData) "valid" else "corrupted"),
    "records" → records,
    "artifacts" → (store.map {
      case (key, value) ⇒ key → Map[String, Any]("count" → value.values.size)
    } toMap)
  ))

  protected def all[A <: Artifact: ClassTag]: List[A] = {
    val `type` = classTag[A].runtimeClass
    log.debug(s"In memory representation: all [${`type`.getSimpleName}]")
    valuesByType(type2string(`type`)).asInstanceOf[List[A]]
  }

  protected def allArtifacts(`type`: Class[_ <: Artifact], page: Int, perPage: Int, filter: (Artifact) ⇒ Boolean): ArtifactResponseEnvelope = {
    log.debug(s"In memory representation: all [${`type`.getSimpleName}] of $page per $perPage")
    val artifacts = valuesByType(type2string(`type`)).filter { artifact ⇒ filter(artifact) }

    val total = artifacts.size
    val (p, pp) = OffsetEnvelope.normalize(page, perPage, ArtifactResponseEnvelope.maxPerPage)
    val (rp, rpp) = OffsetEnvelope.normalize(total, p, pp, ArtifactResponseEnvelope.maxPerPage)
    ArtifactResponseEnvelope(artifacts.slice((p - 1) * pp, p * pp), total, rp, rpp)
  }

  protected def find[A: ClassTag](p: A ⇒ Boolean, `type`: Class[_ <: Artifact]): Option[A] = {
    store.get(type2string(`type`)).flatMap {
      _.find {
        case (_, artifact: A) ⇒ p(artifact)
        case _                ⇒ false
      }
    } map (_._2.asInstanceOf[A])
  }

  protected def readArtifact(name: String, `type`: Class[_ <: Artifact]): Option[Artifact] = {
    log.debug(s"In memory representation: read [${`type`.getSimpleName}] - $name}")
    store.get(type2string(`type`)).flatMap(_.get(name))
  }

  protected def setArtifact(artifact: Artifact): Artifact = {
    log.debug(s"In memory representation: set [${artifact.getClass.getSimpleName}] - ${artifact.name}")
    records += 1
    store.get(type2string(artifact.getClass)) match {
      case None ⇒
        val map = new mutable.HashMap[String, Artifact]()
        map.put(artifact.name, artifact)
        store.put(type2string(artifact.getClass), map)
      case Some(map) ⇒ map.put(artifact.name, artifact)
    }
    artifact
  }

  protected def deleteArtifact(name: String, `type`: String): Option[Artifact] = {
    log.debug(s"In memory representation: delete [${`type`}] - $name}")
    records += 1
    store.get(`type`) flatMap { map ⇒
      val artifact = map.remove(name)
      if (artifact.isEmpty) log.debug(s"Artifact not found for deletion: ${`type`}: $name")
      artifact
    }
  }

  protected def valuesByType(`type`: String): List[Artifact] = store.get(`type`).map(_.values.toList).getOrElse(Nil)
}