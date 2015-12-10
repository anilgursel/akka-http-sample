package com.ebay.myorg

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{StatusCodes, HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.testkit.TestKit
import com.ebay.myorg.RequestContext._
import com.ebay.squbs.rocksqubs.cal.CalLogging
import com.ebay.squbs.rocksqubs.cal.ctx.{CalContext, CalScope}
import org.scalatest.{FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import CalHelper._

/**
 * Created by lma on 10/27/2015.
 */
class PipelineSpec extends TestKit(ActorSystem("RequestContextSpecSys")) with FlatSpecLike with Matchers {

  implicit val fm = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  implicit def calScopeToName(calScope: CalScope): String = {
    calScope match {
      case null => "Unknown"
      case other => other.name
    }
  }

  val startTran = new SyncHandler {
    override def handle(ctx: RequestContext): RequestContext = {
      CalLogging.newTran("TestTran") {
        println("CalScope in startTran: " + CalContext.current)
        ctx.copy()
      }
    }
  }

  val syncException = new SyncHandler {
    override def handle(ctx: RequestContext): RequestContext = {
      throw new RuntimeException("BadMan")
    }
  }

  val syncIn1 = new SyncHandler {
    override def handle(ctx: RequestContext): RequestContext = {
      val name: String = CalContext.current
      ctx.withAttributes("syncIn1" -> name)
    }
  }

  val asyncIn1 = new AsyncHandler {
    override def handle(ctx: RequestContext): Future[RequestContext] = {
      Future.successful(ctx.addRequestHeaders(RawHeader("asyncIn1", CalContext.current)))
    }
  }

  val syncOut1 = new SyncHandler {
    override def handle(ctx: RequestContext): RequestContext = {
      val newResp = ctx.response.map(r => r.copy(headers = r.headers ++ attributes2Headers(ctx.attributes) :+ RawHeader("syncOut1", CalContext.current)))
      ctx.copy(response = newResp)
    }
  }

  val asyncOut1 = new AsyncHandler {
    override def handle(ctx: RequestContext): Future[RequestContext] = {
      val newResp = ctx.response.map(r => r.copy(headers = r.headers ++ ctx.request.headers :+ RawHeader("asyncOut1", CalContext.current)))
      Future {
        ctx.copy(response = newResp)
      }
    }
  }

  val masterAction: HttpRequest => Future[HttpResponse] =
    req => {
      val hs = scala.collection.immutable.Seq(RawHeader("masterAction", CalContext.current))
      Future.successful(HttpResponse(entity = "HelloWorld", headers = hs))
    }

  val masterFlow: Flow[RequestContext, RequestContext, Unit] =
    Flow[RequestContext].mapAsync(1) {
      ctx => cal(ctx, masterAction(ctx.request).map(resp => ctx.copy(response = Option(resp))))
    }

  "Simple flow" should "work" in {

    val pipeline = Pipeline(PipelineSetting(Seq(startTran, asyncIn1, syncIn1), Seq(asyncOut1, syncOut1)), masterFlow)
    val resp = pipeline.run(HttpRequest(), HttpResponse(404, entity = "Unknown resource!"))

    val result = Await.result(resp, 5 seconds)

    result.headers.find(_.name == "syncIn1").get.value() should be("TestTran")
    result.headers.find(_.name == "asyncIn1").get.value() should be("TestTran")
    result.headers.find(_.name == "masterAction").get.value() should be("TestTran")
    result.headers.find(_.name == "syncOut1").get.value() should be("TestTran")
    result.headers.find(_.name == "asyncOut1").get.value() should be("TestTran")
    val sb = new StringBuilder
    Await.result(result.entity.dataBytes.map(_.utf8String).runForeach(sb.append(_)), 3 second)
    sb.toString() should be("HelloWorld")

  }

  "Simple flow without future" should "work" in {

    val pipeline = Pipeline(PipelineSetting(Seq(startTran, syncIn1), Seq(syncOut1)), masterFlow)
    val resp = pipeline.run(HttpRequest(), HttpResponse(404, entity = "Unknown resource!"))

    val result = Await.result(resp, 5 seconds)

    result.headers.find(_.name == "syncIn1").get.value() should be("TestTran")
    result.headers.find(_.name == "masterAction").get.value() should be("TestTran")
    result.headers.find(_.name == "syncOut1").get.value() should be("TestTran")
    val sb = new StringBuilder
    Await.result(result.entity.dataBytes.map(_.utf8String).runForeach(sb.append(_)), 3 second)
    sb.toString() should be("HelloWorld")

  }

  "Simple flow with exception" should "bypass all subsequent handlers" in {

    val pipeline = Pipeline(PipelineSetting(Seq(startTran, asyncIn1, syncException, syncIn1), Seq(asyncOut1, syncOut1)), masterFlow)
    val resp = pipeline.run(HttpRequest(), HttpResponse(404, entity = "Unknown resource!"))

    val result = Await.result(resp, 5 seconds)
    result.status should be(StatusCodes.InternalServerError)
    result.headers.find(_.name == "asyncIn1") should be(None)
    result.headers.find(_.name == "syncIn1") should be(None)
    result.headers.find(_.name == "masterAction") should be(None)
    result.headers.find(_.name == "syncOut1") should be(None)
    result.headers.find(_.name == "asyncOut1") should be(None)
    val sb = new StringBuilder
    Await.result(result.entity.dataBytes.map(_.utf8String).runForeach(sb.append(_)), 3 second)
    sb.toString() should be("BadMan")

  }


}
