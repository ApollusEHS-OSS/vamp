package io.vamp.core.pulse.notification

import io.vamp.common.akka.RequestError
import io.vamp.common.notification.{ErrorNotification, Notification}

case class UnsupportedPulseDriverRequest(request: Any) extends Notification with RequestError

case class PulseResponseError(reason: Any) extends Notification with ErrorNotification

object ElasticsearchInitializationTimeoutError extends Notification
