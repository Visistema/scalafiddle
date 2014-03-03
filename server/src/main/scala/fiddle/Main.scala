package fiddle

import spray.http._
import spray.http.HttpHeaders._
import spray.httpx.encoding.Gzip
import spray.routing.directives.CachingDirectives._
import scala.Some
import spray.http.HttpResponse
import spray.routing.{RequestContext, SimpleRoutingApp}
import akka.actor.ActorSystem
import spray.routing.directives.CacheKeyer
import scala.collection.mutable
import java.security.{AccessControlException, Permission}
import java.io.FilePermission
import java.util.PropertyPermission
import java.lang.reflect.ReflectPermission
import java.net.SocketPermission

object Main extends SimpleRoutingApp {
  implicit val system = ActorSystem()

  /**
   * Only set this once
   */
  lazy val setSecurityManager = System.setSecurityManager(SecurityManager)

  def main(args: Array[String]): Unit = {
    implicit val Default: CacheKeyer = CacheKeyer {
      case RequestContext(HttpRequest(_, uri, _, entity, _), _, _) => (uri, entity)
    }
    val simpleCache = routeCache(maxCapacity = 1000)

    startServer("localhost", port = 8080) {
      cache(simpleCache) {
        get {
          encodeResponse(Gzip) {
            pathSingleSlash {
                getFromResource("index.html")
            } ~
            pathPrefix("js") {
              getFromResourceDirectory("..")
            } ~
            getFromResourceDirectory("")
          }
        } ~
        post {
          path("compile"){
            compileStuff
          }
        }
      }
    }

  }
  def compileStuff(ctx: RequestContext): Unit = try{

    val output = mutable.Buffer.empty[String]
//    setSecurityManager

    val res = Compiler(
      ctx.request.entity.data.toByteArray,
      output.append(_)
    )

    def clean(s: String) = s.replace("\"", "\\\"").replace("\n", "\\n")
    val returned = res match {
      case None =>
        s"""{
          "success": false,
          "logspam": "${clean(output.mkString)}"
        }"""

      case Some(code) =>
        s"""{
          "success": true,
          "logspam": "${clean(output.mkString)}",
          "code": "${clean(code + "ScalaJS.modules.ScalaJSExample().main__AT__V()")}"
        }"""

    }

    ctx.responder ! HttpResponse(
      entity=returned,
      headers=List(
        `Access-Control-Allow-Origin`(spray.http.AllOrigins)
      )
    )
  } catch{case e: AccessControlException =>
    e.printStackTrace()
    ctx.responder ! HttpResponse(
      status=StatusCodes.BadRequest,
      entity="",
      headers=List(
        `Access-Control-Allow-Origin`(spray.http.AllOrigins)
      )
    )
  }
}

/**
 * First approximation security manager that allows the good stuff while
 * blocking everything else. Doesn't block things like infinite-looping
 * or infinite-memory, and is probably full of other holes, but at least
 * obvious bad stuff doesn't work.
 */
object SecurityManager extends java.lang.SecurityManager{
  override def checkPermission(perm: Permission): Unit = {
    perm match{
      case p: FilePermission if p.getActions == "read" =>
        // Needed for the compiler to read class files
      case p: PropertyPermission =>
        // Needed for the filesystem operations to work properly
      case p: ReflectPermission if p.getName == "suppressAccessChecks" =>
        // Needed for scalac to load data from classpath
      case p: SocketPermission if p.getActions == "accept,resolve" =>
        // Needed to continue accepting incoming HTTP requests
      case p: RuntimePermission
        if p.getName == "setContextClassLoader"
        || p.getName == "getenv.*"
        || p.getName == "accessDeclaredMembers" // needed to start htreads, for some reason
        || p.getName == "modifyThreadGroup" // needed for restarts during development to work
        || p.getName == "getenv.SOURCEPATH" =>

      case _ =>
        throw new AccessControlException(perm.toString)
    }
  }
}