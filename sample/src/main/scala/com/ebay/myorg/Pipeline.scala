package com.ebay.myorg

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.ebay.myorg.CalHelper._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Created by lma on 11/2/2015.
 */

sealed trait Handler

trait SyncHandler extends Handler {
  def handle(ctx: RequestContext): RequestContext
}

object DefaultSyncHandler extends SyncHandler {
  override def handle(ctx: RequestContext): RequestContext = ctx
}

trait AsyncHandler extends Handler {
  def handle(ctx: RequestContext): Future[RequestContext]
}

case class PipelineSetting(inbound: Seq[Handler], outbound: Seq[Handler])

case class Pipeline(setting: PipelineSetting,
                    master: Flow[RequestContext, RequestContext, Unit],
                    preInbound: SyncHandler = DefaultSyncHandler,
                    postOutbound: SyncHandler = DefaultSyncHandler)(implicit exec: ExecutionContext, mat: Materializer) {

  import com.ebay.myorg.Pipeline._

  val inbound: Flow[RequestContext, RequestContext, Unit] = genFlow(setting.inbound)
  val outbound: Flow[RequestContext, RequestContext, Unit] = genFlow(setting.outbound)
  val masterFlow: Flow[RequestContext, RequestContext, Unit] = {
    Flow[RequestContext].mapAsync(1) {
      ctx => ctx.error match {
        case None =>
          val result: Future[RequestContext] = Source.single(ctx).via(master).runWith(Sink.head)
          result
        case Some(t) => Future.successful(ctx.copy(response = Option(HttpResponse(500, entity = t.error.getMessage))))
      }
    }
  }

  private def genFlow(handlers: Seq[Handler]) = {
    Flow[RequestContext].mapAsync(1) {
      ctx => process(Left(Success(ctx)), handlers) match {
        case Left(rc) => Future.fromTry(rc)
        case Right(frc) => frc
      }
    }
  }

  private def process(ctx: Either[Try[RequestContext], Future[RequestContext]],
                      rest: Seq[Handler]): Either[Try[RequestContext], Future[RequestContext]] = {

    val newCtx = rest.size match {
      case 0 => ctx
      case _ => (rest(0), ctx) match {
        case (h: SyncHandler, Left(c)) => Left(c.map(handleSync(_, h)))
        case (h: SyncHandler, Right(fc)) => Right(fc.map(handleSync(_, h)))
        case (h: AsyncHandler, Left(c)) => Right(Future.fromTry(c).flatMap(handleAsync(_, h)))
        case (h: AsyncHandler, Right(fc)) => Right(fc.flatMap(handleAsync(_, h)))
      }
    }

    if (rest.size > 1) process(newCtx, rest.drop(1))
    else newCtx
  }

  private def handleAsync(rc: RequestContext, h: AsyncHandler): Future[RequestContext] = {
    cal(rc, {
      rc.error match {
        case None => Try(h.handle(rc)) match {
          case Success(result) => result
          case Failure(t) => Future.successful(rc.copy(error = Option(ErrorLog(t))))
        }
        case _ => Future.successful(rc)
      }
    })
  }

  private def handleSync(rc: RequestContext, h: SyncHandler): RequestContext = {
    cal(rc, {
      rc.error match {
        case None => Try(h.handle(rc)) match {
          case Success(result) => result
          case Failure(t) => rc.copy(error = Option(ErrorLog(t)))
        }
        case _ => rc
      }
    }
    )
  }

  val baseFlow = inbound.via(masterFlow).via(outbound)


  private def compositeSink(defaultResponse: HttpResponse): Sink[RequestContext, Future[HttpResponse]] =
    baseFlow.map(_.response.getOrElse(defaultResponse))
      .toMat(Sink.head)(Keep.right)

  def run(request: HttpRequest): Future[HttpResponse] = {
    run(request, default404)
  }

  def run(request: HttpRequest, defaultResponse: HttpResponse): Future[HttpResponse] = {
    Source.single(RequestContext(request)).runWith(compositeSink(defaultResponse))
  }


  def toFlow(defaultResponse: HttpResponse): Flow[HttpRequest, HttpResponse, Any] = Flow[HttpRequest]
    .map(RequestContext(_))
    .via(baseFlow)
    .map(_.response.getOrElse(defaultResponse))


}

object Pipeline {
  val default404 = HttpResponse(404, entity = "Unknown resource!")
}





