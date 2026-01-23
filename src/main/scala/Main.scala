import zio.*
import zio.http.*

object Main extends ZIOAppDefault:
  
  val app: HttpApp[Any] = 
    Routes(
      Method.GET / "health" -> handler(Response.text("Device Manager is running")),
      Method.GET / "api" / "devices" -> handler(Response.text("Devices endpoint - TODO"))
    ).toHttpApp

  def run =
    for
      _ <- Console.printLine("🚀 Device Manager Service starting...")
      _ <- Console.printLine("📡 REST API will be available on http://0.0.0.0:8081")
      _ <- Server.serve(app).provide(Server.default)
    yield ()
