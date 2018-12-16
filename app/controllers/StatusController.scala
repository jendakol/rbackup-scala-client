package controllers

import com.google.inject.Inject
import play.Environment
import play.api.mvc._
import utils.ConfigProperty

class StatusController @Inject()(cc: ControllerComponents, env: Environment, @ConfigProperty("webpack.port") port: Int)
    extends AbstractController(cc) {
  def status = Action {
    Ok(
      "ok"
    )
  }
}
