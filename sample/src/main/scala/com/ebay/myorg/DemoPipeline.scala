package com.ebay.myorg

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Source, Flow, Keep, Sink}

import scala.concurrent.Future

/**
 * Created by lma on 10/27/2015.
 */
case class DemoPipeline(inbound: Flow[RequestContext, RequestContext, Unit],
                        master: Flow[RequestContext, RequestContext, Unit],
                        outbound: Flow[RequestContext, RequestContext, Unit]) {

  val compositeSink: Sink[RequestContext, Future[HttpResponse]] =
    inbound
    .via(master)
    .via(outbound)
    .map(_.response.getOrElse(HttpResponse(404, entity = "Unknown resource!")))
    .toMat(Sink.head)(Keep.right)

  def run(request: HttpRequest)(implicit materializer: Materializer): Future[HttpResponse] = {
    Source.single(RequestContext(request)).runWith(compositeSink)
  }
}

object DemoPipeline{

  implicit def funcToFlow[In, Out](function : In => Out): Flow[In, Out, Unit] = {
      Flow[In].map(function)
  }

  implicit def asyncFuncToFlow[In, Out](function : In => Future[Out]): Flow[In, Out, Unit] = {
    Flow[In].mapAsync(1)(function)
  }
}
