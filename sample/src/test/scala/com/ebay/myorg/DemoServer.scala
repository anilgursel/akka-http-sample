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
import akka.stream.scaladsl._
import akka.stream.stage.{OutHandler, InHandler, GraphStageLogic, GraphStage}
import akka.stream._
import akka.util.Timeout
import com.ebay.myorg.RequestContext._
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.io.StdIn

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

  type Transformer = HttpRequest => Future[HttpResponse]

  case class ContextHolder(ctx: RequestContext, transformer: Option[Transformer])


  val inbound: Flow[ContextHolder, ContextHolder, Any] = Flow[ContextHolder].map(holder => holder.copy(ctx = holder.ctx.withAttributes("key1" -> "value1").addRequestHeaders(RawHeader("reqHeader", "reqHeaderValue"))))

  val outbound: Flow[RequestContext, RequestContext, Any] = Flow[RequestContext].map {
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


  val dispatchFlow: Flow[HttpRequest, HttpResponse, Any] =
    Flow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val zip = b.add(Zip[HttpRequest, Int]())
      val broadcast = b.add(Broadcast[ContextHolder](2))
      val merge = b.add(Merge[RequestContext](2))
      val pre = b.add(Flow[(HttpRequest, Int)].map {
        case (request, id) =>
          services.find { entry =>
          request.uri.path.startsWith(entry._1)
        } match {
          case Some((p, Right(r))) => ContextHolder(RequestContext(request, id), Some(Route.asyncHandler(pathPrefix(p.tail.toString())(r))))
          case Some((_, Left(actor))) => ContextHolder(RequestContext(request, id), Some({ req: HttpRequest => (actor ? req).mapTo[HttpResponse]}))
          case _ => ContextHolder(RequestContext(request, id), None)
        }
      })

      val goodFilter = Flow[ContextHolder].filter(_.transformer.isDefined)
      val badFilter = Flow[ContextHolder].filter(_.transformer.isEmpty)
      val coreFlow = Flow[ContextHolder].mapAsync(1) {
        ch => ch.transformer.get.apply(ch.ctx.request).map(resp => ch.ctx.copy(response = Option(resp)))
      }

      val respFlow = b.add(Flow[RequestContext].map(_.response.getOrElse(HttpResponse(404, entity = "Unknown resource!"))))

      object RequestContextOrdering extends Ordering[RequestContext] {
        def compare(a:RequestContext, b:RequestContext) = b.id compare a.id
      }

      val orderingStage = b.add(new OrderingStage[RequestContext, Int](0, (x: Int) => x + 1, (rc: RequestContext) => rc.id)(RequestContextOrdering))

      Source.fromIterator(() => Iterator.from(0)) ~> zip.in1
      zip.out ~> pre ~> broadcast ~> goodFilter ~> inbound ~> coreFlow ~> outbound  ~> merge ~> orderingStage ~> respFlow
                        broadcast ~> badFilter.map(_.ctx)                           ~> merge

      // expose ports
      FlowShape(zip.in0, respFlow.out)
    })

  val bindingFuture = Http().bindAndHandle(dispatchFlow, interface = "localhost", port = 9001)


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
      sender() ! HttpResponse(entity = HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, source))

  }
}
