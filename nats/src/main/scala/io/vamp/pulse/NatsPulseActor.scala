package io.vamp.pulse

import akka.actor.Actor
import io.vamp.common.{ClassMapper, Config, ConfigMagnet, Namespace}
import io.vamp.common.json.{OffsetDateTimeSerializer, SerializationFormat}
import io.vamp.common.vitals.{InfoRequest, StatsRequest}
import io.vamp.model.event._
import io.vamp.model.resolver.NamespaceValueResolver
import io.vamp.pulse.Percolator.{GetPercolator, RegisterPercolator, UnregisterPercolator}
import io.vamp.pulse.notification._
import org.json4s.ext.EnumNameSerializer
import org.json4s.native.Serialization.{read, write}
import org.json4s.{DefaultFormats, Extraction, Formats}

import scala.concurrent.Future
import scala.util.{Random, Try}
import io.nats.streaming.StreamingConnection
import io.nats.streaming.AckHandler
import io.vamp.common.akka.IoC

class NatsPulseActorMapper extends ClassMapper {
  val name = "nats"
  val clazz: Class[_] = classOf[NatsPulseActor]
}

object NatsPulseActor {

  val config: String = PulseActor.config

  val natsUrl: ConfigMagnet[String] = Config.string(s"$config.nats.url")
  val clusterId: ConfigMagnet[String] = Config.string(s"$config.nats.cluster-id")
  val clientId: ConfigMagnet[String] = Config.string(s"$config.nats.client-id")

}

/**
 * NATS Pulse Actor pushes messages to NATS and also forwards other types of messages to Elasticsearch
 *
 * If you are here since messages are slow check this:
 * https://github.com/nats-io/java-nats-streaming#linux-platform-note
 */
class NatsPulseActor extends NamespaceValueResolver with PulseActor {

  import PulseActor._

  import io.nats.streaming.StreamingConnectionFactory

  val randomId = Random.alphanumeric.take(5).mkString("")

  val clusterId = NatsPulseActor.clusterId()
  val clientId = s"${NatsPulseActor.clientId()}/${namespace.name}/$randomId"
  val natsUrl = NatsPulseActor.natsUrl()
  val cf = {
    val scf = new StreamingConnectionFactory(clusterId, clientId)
    scf.setNatsUrl(natsUrl)
    scf
  }
  var sc: StreamingConnection = _

  // The ack handler will be invoked when a publish acknowledgement is received
  // This is a def, not a val due to possible concurrency issues
  // ackHandler is only used in asynchronous case
  def ackHandler: AckHandler = (guid: String, err: Exception) ⇒ {
    if (err != null)
      logger.error("Error publishing msg id %s: %s\n", guid, err.getMessage)
    else
      logger.info("Received ack for msg id %s\n", guid)
  }

  /**
   * Starts a logical connection to the NATS cluster
   * Documentation: https://github.com/nats-io/java-nats-streaming#basic-usage
   */
  override def preStart(): Unit = {
    sc = cf.createConnection
  }

  override def postStop(): Unit = {
    Try(sc.close())
  }

  def receive: Actor.Receive = {

    case InfoRequest ⇒ reply(info)

    case StatsRequest ⇒ IoC.actorFor[PulseActorSupport].forward(StatsRequest)

    case Publish(event, publishEventValue) ⇒ reply((validateEvent andThen publish(publishEventValue) andThen broadcast(publishEventValue))(Event.expandTags(event)), classOf[EventIndexError])

    case Query(envelope) ⇒ IoC.actorFor[PulseActorSupport].forward(Query(envelope))

    case GetPercolator(name) ⇒ reply(Future.successful(getPercolator(name)))

    case RegisterPercolator(name, tags, kind, message) ⇒ registerPercolator(name, tags, kind, message)

    case UnregisterPercolator(name) ⇒ unregisterPercolator(name)

    case any ⇒ unsupported(UnsupportedPulseRequest(any))
  }

  private def info = Future { Map[String, Any]("type" → "nats", "nats" → clusterId) }

  private def publish(publishEventValue: Boolean)(event: Event): Future[Any] = Future {
    // Send it to Elasticsearch
    IoC.actorFor[PulseActorSupport] ! Publish(event, publishEventValue)

    implicit val formats: Formats = SerializationFormat(OffsetDateTimeSerializer, new EnumNameSerializer(Aggregator))

    val attachment = (publishEventValue, event.value) match {
      case (true, str: String) ⇒ Map(typeName → str)
      case (true, any)         ⇒ Map("value" → write(any)(DefaultFormats), typeName → (if (typeName == Event.defaultType) "" else any))
      case (false, _)          ⇒ Map("value" → "")
    }

    val data = Extraction.decompose(if (publishEventValue) event else event.copy(value = None)) merge Extraction.decompose(attachment)

    val subject = s"${namespace.name}/$typeName/${event.tags}"

    logger.info(s"NATS: Pulse publish an event with subject $subject and data: ${data.toString}")
    // This is a synchronous (blocking) call
    // This can throw an exception currently it is unhandled so actor will be restarted with the same message
    sc.publish(subject, data.toString.getBytes)

    // TODO: following method is asynchronous, try asynchronous connections later if feasible
    // sc.publish(subject, data.toString.getBytes, ackHandler)
  }

  private def broadcast(publishEventValue: Boolean): Future[Any] ⇒ Future[Any] = _.map {
    case event: Event ⇒ percolate(publishEventValue)(event)
    case other        ⇒ other
  }
}