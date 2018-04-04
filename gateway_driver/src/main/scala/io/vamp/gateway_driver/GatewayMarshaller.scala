package io.vamp.gateway_driver

import io.vamp.model.artifact._

trait GatewayMarshaller {

  def info: AnyRef

  def `type`: String

  def marshall(gateways: List[Gateway], template: String): String
}
