package com.ebay.myorg

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by lma on 11/2/2015.
 */

sealed trait Handler

trait SyncHandler extends Handler {
  def handle(ctx: RequestContext): RequestContext
}

trait AsyncHandler extends Handler {
  def handle(ctx: RequestContext): Future[RequestContext]
}

case class PipelineSetting(inbound: Seq[Handler], outbound: Seq[Handler])

case class Pipeline(setting: PipelineSetting,
                    master: Flow[RequestContext, RequestContext, Unit])(implicit exec : ExecutionContext) {


  val inbound: Flow[RequestContext, RequestContext, Unit] = genFlow(setting.inbound)
  val outbound: Flow[RequestContext, RequestContext, Unit] = genFlow(setting.outbound)

  private def genFlow(handlers: Seq[Handler]) = {
    Flow[RequestContext].mapAsync(1) {
      ctx => process(Left(ctx), handlers) match {
        case Left(rc) => Future.successful(rc)
        case Right(frc) => frc
      }
    }
  }

  private def process(ctx: Either[RequestContext, Future[RequestContext]],
                      rest: Seq[Handler]): Either[RequestContext, Future[RequestContext]] = {

    val newCtx = rest.size match {
      case 0 => ctx
      case _ => (rest(0), ctx) match {
        case (h: SyncHandler, Left(c)) => Left(h.handle(c))
        case (h: SyncHandler, Right(fc)) => Right(fc.map(r => h.handle(r)))
        case (h: AsyncHandler, Left(c)) => Right(h.handle(c))
        case (h: AsyncHandler, Right(fc)) => Right(fc.flatMap(r => h.handle(r)))
      }
    }

    if (rest.size > 1) process(newCtx, rest.drop(1))
    else newCtx
  }


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





