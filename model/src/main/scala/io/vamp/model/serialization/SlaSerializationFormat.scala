package io.vamp.model.serialization

import io.vamp.common.Type
import io.vamp.model.artifact._
import org.json4s.JsonAST.JString
import org.json4s._

import scala.collection.mutable.ArrayBuffer

object SlaSerializationFormat extends io.vamp.common.json.SerializationFormat {

  override def customSerializers = super.customSerializers :+
    new SlaSerializer() :+
    new EscalationSerializer()
}

class SlaSerializer extends ArtifactSerializer[Sla] with ReferenceSerialization {
  override def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
    case sla: SlaReference ⇒ new JObject(serializeReferenceAsField(sla) :: JField("escalations", Extraction.decompose(sla.escalations)) :: Nil)

    case sla: ResponseTimeSlidingWindowSla ⇒
      val list = new ArrayBuffer[JField]
      if (sla.name.nonEmpty) {
        list += JField("name", JString(sla.name))
        list += JField("kind", JString(sla.kind))
        list += JField("metadata", Extraction.decompose(sla.metadata)(DefaultFormats))
      }
      list += JField("type", JString("response_time_sliding_window"))
      list += JField("window", Extraction.decompose(Map("interval" → sla.interval.toSeconds, "cooldown" → sla.cooldown.toSeconds)))
      list += JField("threshold", Extraction.decompose(Map("upper" → sla.upper.toMillis, "lower" → sla.lower.toMillis)))
      list += JField("escalations", Extraction.decompose(sla.escalations))
      new JObject(list.toList)

    case sla: GenericSla ⇒
      val list = new ArrayBuffer[JField]
      if (sla.name.nonEmpty) {
        list += JField("name", JString(sla.name))
        list += JField("kind", JString(sla.kind))
        list += JField("metadata", Extraction.decompose(sla.metadata)(DefaultFormats))
      }
      list += JField("type", JString(sla.`type`))
      list += JField("parameters", Extraction.decompose(sla.parameters))
      list += JField("escalations", Extraction.decompose(sla.escalations))
      new JObject(list.toList)

    case sla: EscalationOnlySla ⇒
      val list = new ArrayBuffer[JField]
      if (sla.name.nonEmpty) {
        list += JField("name", JString(sla.name))
        list += JField("kind", JString(sla.kind))
        list += JField("metadata", Extraction.decompose(sla.metadata)(DefaultFormats))
      }
      list += JField("type", JString(sla.`type`))
      list += JField("escalations", Extraction.decompose(sla.escalations))
      new JObject(list.toList)
  }
}

class EscalationSerializer extends ArtifactSerializer[Escalation] with ReferenceSerialization {
  override def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
    case escalation: EscalationReference ⇒ serializeReference(escalation)

    case escalation: GroupEscalation ⇒
      val list = new ArrayBuffer[JField]
      if (escalation.name.nonEmpty) {
        list += JField("name", JString(escalation.name))
        list += JField("kind", JString(escalation.kind))
        list += JField("metadata", Extraction.decompose(escalation.metadata)(DefaultFormats))
      }
      list += JField("type", JString(escalation.`type`))
      list += JField("escalations", Extraction.decompose(escalation.escalations))
      new JObject(list.toList)

    case escalation: ScaleEscalation ⇒
      val list = new ArrayBuffer[JField]
      if (escalation.name.nonEmpty) {
        list += JField("name", JString(escalation.name))
        list += JField("kind", JString(escalation.kind))
        list += JField("metadata", Extraction.decompose(escalation.metadata)(DefaultFormats))
      }

      list += JField("type", JString(escalation.`type`))

      if (escalation.targetCluster.nonEmpty)
        list += JField("target", JString(escalation.targetCluster.get))

      escalation match {
        case ScaleInstancesEscalation(_, _, minimum, maximum, scaleBy, _) ⇒ {
          list += JField("minimum", Extraction.decompose(minimum))
          list += JField("maximum", Extraction.decompose(maximum))
          list += JField("scale_by", Extraction.decompose(scaleBy))
        }
        case ScaleCpuEscalation(_, _, minimum, maximum, scaleBy, _) ⇒ {
          list += JField("minimum", Extraction.decompose(minimum))
          list += JField("maximum", Extraction.decompose(maximum))
          list += JField("scale_by", Extraction.decompose(scaleBy))
        }
        case ScaleMemoryEscalation(_, _, minimum, maximum, scaleBy, _) ⇒ {
          list += JField("minimum", Extraction.decompose(minimum))
          list += JField("maximum", Extraction.decompose(maximum))
          list += JField("scale_by", Extraction.decompose(scaleBy))
        }
      }

      new JObject(list.toList)

    case escalation: Escalation ⇒
      val list = new ArrayBuffer[JField]
      if (escalation.name.nonEmpty) {
        list += JField("name", JString(escalation.name))
        list += JField("kind", JString(escalation.kind))
        list += JField("metadata", Extraction.decompose(escalation.metadata)(DefaultFormats))
      }
      escalation match {
        case g: GenericEscalation ⇒
          list += JField("type", JString(g.`type`))
          list += JField("parameters", Extraction.decompose(g.parameters))
        case t: Type ⇒
          list += JField("type", JString(t.`type`))
        case _ ⇒
      }
      new JObject(list.toList)
  }
}
