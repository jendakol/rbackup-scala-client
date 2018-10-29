package controllers

import com.google.inject.Inject
import lib.WebpackBuildFile
import play.Environment
import play.api.mvc._
import utils.ConfigProperty

class FrontController @Inject()(cc: ControllerComponents, env: Environment, @ConfigProperty("webpack.port") port: Int)
    extends AbstractController(cc) {
  def index = Action {
    Ok(views.html.index.render(env, port, WebpackBuildFile.jsBundleName, WebpackBuildFile.cssBundleName))
  }
}
