package controllers

import com.typesafe.scalalogging.StrictLogging
import play.api.mvc.RequestHeader
import utils.AllowedApiOrigins

// imported from https://github.com/playframework/play-scala-websocket-example
trait SameOriginCheck extends StrictLogging {

  protected def allowedOrigins: AllowedApiOrigins

  /**
    * Checks that the WebSocket comes from the same origin.  This is necessary to protect
    * against Cross-Site WebSocket Hijacking as WebSocket does not implement Same Origin Policy.
    *
    * See https://tools.ietf.org/html/rfc6455#section-1.3 and
    * http://blog.dewhurstsecurity.com/2013/08/30/security-testing-html5-websockets.html
    */
  def sameOriginCheck(rh: RequestHeader): Boolean = {
    rh.headers.get("Origin") match {
      case Some(originValue) if allowedOrigins.values.contains(originValue) =>
        logger.debug(s"OriginCheck: originValue = $originValue")
        true

      case Some(badOrigin) =>
        logger.debug(s"Rejecting request because Origin header value '$badOrigin' is not in the same origin")
        false

      case None =>
        logger.debug("Rejecting request because no Origin header found")
        false
    }
  }
}
