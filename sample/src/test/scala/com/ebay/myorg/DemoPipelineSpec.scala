package com.ebay.myorg

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, HttpRequest}
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.testkit.TestKit
import com.ebay.myorg.RequestContext._
import org.scalatest.{Matchers, FlatSpecLike}

import scala.concurrent.{Await, Future}
import DemoPipeline._
import scala.concurrent.duration._

/**
 * Created by lma on 10/27/2015.
 */
class DemoPipelineSpec extends TestKit(ActorSystem("RequestContextSpecSys")) with FlatSpecLike with Matchers {

  import system.dispatcher

  implicit val fm = ActorMaterializer()

  val syncInbound: RequestContext => RequestContext =
    ctx => ctx.withAttributes("key1" -> "value1").addRequestHeaders(RawHeader("reqHeader", "reqHeaderValue"))

  val badInbound : RequestContext => RequestContext =
    ctx => throw new IllegalArgumentException("BadMan")

  val syncOutbound: RequestContext => RequestContext =
    ctx => {
      val newResp = ctx.response.map(r => r.copy(headers = r.headers ++ attributes2Headers(ctx.attributes) ++ ctx.request.headers))
      ctx.copy(response = newResp)
    }

  val masterAction: HttpRequest => Future[HttpResponse] =
    req => Future.successful(HttpResponse(entity = "HelloWorld"))

  val masterFlow: Flow[RequestContext, RequestContext, Unit] =
    Flow[RequestContext].mapAsync(1) {
      ctx => masterAction(ctx.request).map(resp => ctx.copy(response = Option(resp)))
    }

  "Simple flow" should "work" in {

    val pipeline = DemoPipeline(syncInbound, masterFlow, syncOutbound)
    val resp = pipeline.run(HttpRequest())
    val result = Await.result(resp, 5 seconds)

    result.headers.find(_.name == "key1").isDefined should be(true)
    result.headers.find(_.name == "reqHeader").isDefined should be(true)
    val sb = new StringBuilder
    Await.result(result.entity.dataBytes.map(_.utf8String).runForeach(sb.append(_)), 3 second)
    sb.toString() should be("HelloWorld")

  }



}
