package io.vamp.persistence

import akka.actor.Actor
import akka.pattern.ask
import io.vamp.common.{ Id, Namespace }
import io.vamp.common.akka.CommonSupportForActors
import io.vamp.model.artifact.DeploymentService.Status
import io.vamp.model.artifact._
import io.vamp.persistence.refactor.VampPersistence
import io.vamp.persistence.refactor.serialization.VampJsonFormats

import scala.concurrent.Future

trait DeploymentPersistenceMessages {

  case class UpdateDeploymentServiceStatus(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, status: DeploymentService.Status) extends PersistenceActor.PersistenceMessages

  case class UpdateDeploymentServiceScale(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, scale: DefaultScale, source: String) extends PersistenceActor.PersistenceMessages

  case class UpdateDeploymentServiceInstances(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, instances: List[Instance]) extends PersistenceActor.PersistenceMessages

  case class UpdateDeploymentServiceEnvironmentVariables(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, environmentVariables: List[EnvironmentVariable]) extends PersistenceActor.PersistenceMessages

  case class UpdateDeploymentServiceHealth(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, health: Health) extends PersistenceActor.PersistenceMessages

  case class ResetDeploymentService(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService) extends PersistenceActor.PersistenceMessages

}

object DeploymentPersistenceOperations extends VampJsonFormats {
  import scala.concurrent.ExecutionContext.Implicits.global

  def updateServiceStatus(deployment: Deployment, deploymentCluster: DeploymentCluster, deploymentService: DeploymentService, serviceStatus: Status)(implicit ns: Namespace): Future[Unit] = {
    VampPersistence().update[Deployment](deploymentSerilizationSpecifier.idExtractor(deployment), (d: Deployment) ⇒ {
      val mServices = d.clusters.find(c ⇒ c.name == deploymentCluster.name).get
        .services.map(s ⇒ if (s.breed.name == deploymentService.breed.name) s.copy(status = serviceStatus) else s)
      val clusters = d.clusters.map(c ⇒ if (c.name == deploymentCluster.name) c.copy(services = mServices) else c)
      d.copy(clusters = clusters)
    })
  }

  def updateServiceScale(deployment: Deployment, deploymentCluster: DeploymentCluster, deploymentService: DeploymentService, serviceScale: DefaultScale, source: String)(implicit ns: Namespace): Future[Unit] = {
    // TODO: source is ignored
    VampPersistence().update[Deployment](deploymentSerilizationSpecifier.idExtractor(deployment), (d: Deployment) ⇒ {
      val mServices = d.clusters.find(c ⇒ c.name == deploymentCluster.name).get
        .services.map(s ⇒ if (s.breed.name == deploymentService.breed.name) s.copy(scale = Option(serviceScale)) else s)
      val clusters = d.clusters.map(c ⇒ if (c.name == deploymentCluster.name) c.copy(services = mServices) else c)
      d.copy(clusters = clusters)
    })
  }

  def updateServiceHealth(deployment: Deployment, deploymentCluster: DeploymentCluster, deploymentService: DeploymentService, serviceHealth: Health)(implicit ns: Namespace): Future[Unit] = {
    VampPersistence().update[Deployment](deploymentSerilizationSpecifier.idExtractor(deployment), (d: Deployment) ⇒ {
      val mServices = d.clusters.find(c ⇒ c.name == deploymentCluster.name).get
        .services.map(s ⇒ if (s.breed.name == deploymentService.breed.name) s.copy(health = Option(serviceHealth)) else s)
      val clusters = d.clusters.map(c ⇒ if (c.name == deploymentCluster.name) c.copy(services = mServices) else c)
      d.copy(clusters = clusters)
    })
  }

  def updateServiceEnvironmentVariables(deployment: Deployment, deploymentCluster: DeploymentCluster, deploymentService: DeploymentService, envVars: List[EnvironmentVariable])(implicit ns: Namespace): Future[Unit] = {
    VampPersistence().update[Deployment](deploymentSerilizationSpecifier.idExtractor(deployment), (d: Deployment) ⇒ {
      val mServices = d.clusters.find(c ⇒ c.name == deploymentCluster.name).get
        .services.map(s ⇒ if (s.breed.name == deploymentService.breed.name) s.copy(environmentVariables = envVars) else s)
      val clusters = d.clusters.map(c ⇒ if (c.name == deploymentCluster.name) c.copy(services = mServices) else c)
      d.copy(clusters = clusters)
    })
  }

  def updateServiceInstances(deployment: Deployment, deploymentCluster: DeploymentCluster, deploymentService: DeploymentService, serviceInstances: List[Instance])(implicit ns: Namespace): Future[Unit] = {
    VampPersistence().update[Deployment](deploymentSerilizationSpecifier.idExtractor(deployment), (d: Deployment) ⇒ {
      val mServices = d.clusters.find(c ⇒ c.name == deploymentCluster.name).get
        .services.map(s ⇒ if (s.breed.name == deploymentService.breed.name) s.copy(instances = serviceInstances) else s)
      val clusters = d.clusters.map(c ⇒ if (c.name == deploymentCluster.name) c.copy(services = mServices) else c)
      d.copy(clusters = clusters)
    })
  }

  def resetDeploymentService(deployment: Deployment, deploymentCluster: DeploymentCluster, deploymentService: DeploymentService)(implicit ns: Namespace): Future[Unit] = {
    VampPersistence().update[Deployment](deploymentSerilizationSpecifier.idExtractor(deployment), (d: Deployment) ⇒ {
      val mServices = d.clusters.find(c ⇒ c.name == deploymentCluster.name).get
        .services.map(s ⇒ if (s.breed.name == deploymentService.breed.name) s.copy(
          scale = None,
          instances = List[Instance](),
          environmentVariables = List[EnvironmentVariable](),
          health = None)
        else s)
      val clusters = d.clusters.map(c ⇒ if (c.name == deploymentCluster.name) c.copy(services = mServices) else c)
      d.copy(clusters = clusters)
    })
  }

  def resetGateway(deployment: Deployment, deploymentCluster: DeploymentCluster, deploymentService: DeploymentService)(implicit ns: Namespace): Future[Unit] = {
    // TODO: check if this is valid
    val name = serviceArtifactName(deployment, deploymentCluster, deploymentService)
    VampPersistence().deleteObject[Gateway](Id[Gateway](name))
  }

  // resetInternalRouteArtifacts
  def resetInternalRouteArtifacts(deployment: Deployment, deploymentCluster: DeploymentCluster, deploymentService: DeploymentService)(implicit ns: Namespace): Unit = {
    // TODO: check if this is valid
    deploymentService.breed.ports.foreach { port ⇒
      {
        deploymentCluster.gatewayBy(port.name) match {
          case Some(gateway) ⇒ VampPersistence().deleteObject[Gateway](gatewaySerilizationSpecifier.idExtractor(gateway))
          case None          ⇒ // no internal gateway
        }
      }
    }
  }

  def clusterArtifactName(deployment: Deployment, cluster: DeploymentCluster): String = {
    GatewayPath(deployment.name :: cluster.name :: Nil).normalized
  }

  def serviceArtifactName(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService): String = {
    GatewayPath(deployment.name :: cluster.name :: service.breed.name :: Nil).normalized
  }

  def servicePortArtifactName(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, port: Port): String = {
    GatewayPath(deployment.name :: cluster.name :: service.breed.name :: port.name :: Nil).normalized
  }
}

trait DeploymentPersistenceOperations {
  this: CommonSupportForActors with PersistenceArchive ⇒

  import PersistenceActor._
  import DeploymentPersistenceOperations._

  def receive: Actor.Receive = {

    case o: UpdateDeploymentServiceStatus               ⇒ updateStatus(o.deployment, o.cluster, o.service, o.status)

    case o: UpdateDeploymentServiceScale                ⇒ updateScale(o.deployment, o.cluster, o.service, o.scale, o.source)

    case o: UpdateDeploymentServiceInstances            ⇒ updateInstances(o.deployment, o.cluster, o.service, o.instances)

    case o: UpdateDeploymentServiceEnvironmentVariables ⇒ updateEnvironmentVariables(o.deployment, o.cluster, o.service, o.environmentVariables)

    case o: UpdateDeploymentServiceHealth               ⇒ updateServiceHealth(o.deployment, o.cluster, o.service, o.health)

    case o: ResetDeploymentService                      ⇒ reset(o.deployment, o.cluster, o.service)
  }

  private def updateStatus(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, status: DeploymentService.Status) = reply {
    self ? PersistenceActor.Update(DeploymentServiceStatus(serviceArtifactName(deployment, cluster, service), status))
  }

  private def updateScale(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, scale: DefaultScale, source: String) = reply {
    val artifact = DeploymentServiceScale(serviceArtifactName(deployment, cluster, service), scale)
    self ? PersistenceActor.Update(artifact, Option(source))
  }

  private def updateInstances(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, instances: List[Instance]) = reply {
    self ? PersistenceActor.Update(DeploymentServiceInstances(serviceArtifactName(deployment, cluster, service), instances))
  }

  private def updateEnvironmentVariables(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, environmentVariables: List[EnvironmentVariable]) = reply {
    self ? PersistenceActor.Update(DeploymentServiceEnvironmentVariables(serviceArtifactName(deployment, cluster, service), environmentVariables))
  }

  private def updateServiceHealth(
    deployment: Deployment,
    cluster:    DeploymentCluster,
    service:    DeploymentService,
    health:     Health
  ) = reply {
    self ? PersistenceActor.Update(
      DeploymentServiceHealth(serviceArtifactName(deployment, cluster, service), health)
    )
  }

  private def reset(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService) = reply {
    val name = serviceArtifactName(deployment, cluster, service)

    val messages = PersistenceActor.Delete(name, classOf[DeploymentServiceScale]) ::
      PersistenceActor.Delete(name, classOf[DeploymentServiceInstances]) ::
      PersistenceActor.Delete(name, classOf[DeploymentServiceEnvironmentVariables]) ::
      PersistenceActor.Delete(name, classOf[DeploymentServiceHealth]) :: Nil

    Future.sequence(messages.map(self ? _))
  }
}

private[persistence] object DeploymentServiceStatus {
  val kind: String = "deployment-service-statuses"
}

case class DeploymentServiceStatus(name: String, status: DeploymentService.Status) extends PersistenceArtifact {
  val kind: String = DeploymentServiceStatus.kind
}

private[persistence] object DeploymentServiceScale {
  val kind: String = "deployment-service-scales"
}

case class DeploymentServiceScale(name: String, scale: DefaultScale) extends PersistenceArtifact {
  val kind: String = DeploymentServiceScale.kind
}

private[persistence] object DeploymentServiceInstances {
  val kind: String = "deployment-service-instances"
}

case class DeploymentServiceInstances(name: String, instances: List[Instance]) extends PersistenceArtifact {
  val kind: String = DeploymentServiceInstances.kind
}

private[persistence] object DeploymentServiceEnvironmentVariables {
  val kind: String = "deployment-service-environment-variables"
}

case class DeploymentServiceEnvironmentVariables(name: String, environmentVariables: List[EnvironmentVariable]) extends PersistenceArtifact {
  val kind: String = DeploymentServiceEnvironmentVariables.kind
}

private[persistence] object DeploymentServiceHealth {
  val kind: String = "deployment-service-healths"
}

case class DeploymentServiceHealth(name: String, health: Health) extends PersistenceArtifact {
  val kind: String = DeploymentServiceHealth.kind
}