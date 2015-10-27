package com.ebay.myorg

import akka.actor.{Actor, ActorRef, ActorSystem, Props, _}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity.{Chunk, LastChunk}
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.io.StdIn
import RequestContext._

object DemoServer extends App {
  val testConf: Config = ConfigFactory.parseString( """
    akka.loglevel = INFO
    akka.log-dead-letters = off
                                                    """)
  implicit val system = ActorSystem("DemoServer", testConf)

  import com.ebay.myorg.DemoServer.system.dispatcher

  implicit val fm = ActorMaterializer()
  implicit val askTimeOut: Timeout = 5 seconds

  //http://localhost:9001/route/index
  val routeDef: Route = get {
    path("index") {
      complete("From Route!")
    }
  }

  val inbound: Flow[RequestContext, RequestContext, Unit] = Flow[RequestContext].map(ctx => ctx.withAttributes("key1" -> "value1").addRequestHeaders(RawHeader("reqHeader", "reqHeaderValue")))

  val outbound: Flow[RequestContext, RequestContext, Unit] = Flow[RequestContext].map {
    ctx =>
      val newResp = ctx.response.map(r => r.copy(headers = r.headers ++ attributes2Headers(ctx.attributes) ++ ctx.request.headers))
      ctx.copy(response = newResp)
  }


  //http://localhost:9001/actor
  val actorRef = system.actorOf(Props(classOf[DemoActor]))

  //TODO: Better to be modeled as Map[Path, Flow[HttpRequest, HttpResponse, Unit]] in the concrete impl
  val services: Map[Path, Either[ActorRef, Route]] =
    Map.empty[Path, Either[ActorRef, Route]] +
      (Path("/route") -> Right(routeDef)) +
      (Path("/actor") -> Left(actorRef))


  val bindingFuture = Http().bindAndHandleAsync({
    request => services.find { entry =>
      request.uri.path.startsWith(entry._1)
    } match {
      case Some(e) =>
        val masterFlow: Flow[RequestContext, RequestContext, Unit] = Flow[RequestContext].mapAsync(1) {
          ctx =>
            val response: Future[HttpResponse] = e._2 match {
              case Right(r) => Source.single(ctx.request).via(pathPrefix(e._1.tail.toString())(r)).runWith(Sink.head)
              case Left(actor) => (actor ? ctx.request).mapTo[HttpResponse]
            }
            response.map(resp => ctx.copy(response = Option(resp)))
        }

        Source.single(RequestContext(request))
          .via(inbound)
          .via(masterFlow)
          .via(outbound)
          .map(_.response.getOrElse(HttpResponse(404, entity = "Unknown resource!")))
          .runWith(Sink.head)


      case None => Future.successful(HttpResponse(404, entity = "Unknown resource!"))
    }
  }, interface = "localhost", port = 9001)

  Await.result(bindingFuture, 2.second) // throws if binding fails
  println("Server online at http://localhost:9001")

  val clientFlow = Http().outgoingConnection("localhost", 9001)

  Source(List(HttpRequest(uri = "/route/index"), HttpRequest(uri = "/actor")))
    .via(clientFlow)
    .runForeach {
    resp =>
      println(resp)
      println("Response Body:")
      val result = resp.entity.dataBytes.map(_.utf8String).runForeach(println)
      Await.result(result, 2.second)
  }
  //      .via(Framing.delimiter(ByteString("\r\n"), maximumFrameLength = 100, allowTruncation = true))
  //      .map(_.utf8String)
  //      .runForeach(println))


  println("Press RETURN to stop...")
  StdIn.readLine()

  bindingFuture.flatMap(_.unbind()).onComplete(_ ⇒ system.shutdown())


}



class DemoActor extends Actor {
  implicit val mat = ActorMaterializer()

  override def receive: Receive = {
    case req: HttpRequest =>
      //                  val source: Source[ChunkStreamPart, ActorRef] = Source.actorRef(10, OverflowStrategy.fail)
      //                  val actorRef = source.to(Sink.head).run()
      //                  actorRef ! Chunk("Hello")
      //                  actorRef ! Chunk("World")
      //                  actorRef ! LastChunk()

      val source = Source(List(Chunk("From"), Chunk("Actor"), LastChunk))
      sender() ! HttpResponse(entity = HttpEntity.Chunked(ContentTypes.`text/plain`, source))

  }
}

