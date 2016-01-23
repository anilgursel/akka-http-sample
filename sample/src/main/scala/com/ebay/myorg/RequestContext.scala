package com.ebay.myorg

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import com.ebay.squbs.rocksqubs.cal.ctx.{CalScope, CalScopeAware}

/**
 * Created by lma on 10/27/2015.
 */
//TODO better naming
case class ErrorLog(error : Throwable)

case class RequestContext(request: HttpRequest,
                          id: Int = 1,
                          response: Option[HttpResponse] = None,
                          attributes: Map[String, Any] = Map.empty,
                          error : Option[ErrorLog] = None) extends CalScopeAware {

  val calScope: CalScope = CalScopeAware.default.calScope

  def withAttributes(attributes: (String, Any)*): RequestContext = {
    this.copy(attributes = this.attributes ++ attributes)
  }

  def attribute[T](key: String): Option[T] = {
    attributes.get(key) match {
      case None => None
      case Some(null) => None
      case Some(value) => Some(value.asInstanceOf[T])
    }
  }

  def addRequestHeaders(headers: HttpHeader*): RequestContext = {
    copy(request = request.copy(headers = request.headers ++ headers))
  }

  def addResponseHeaders(headers: HttpHeader*): RequestContext = {
    response.fold(this) {
      resp => copy(response = Option(resp.copy(headers = request.headers ++ headers)))
    }
  }


}

object RequestContext {

  implicit def attributes2Headers(attributes: Map[String, Any]): Seq[HttpHeader] = {
    attributes.toSeq.map {
      attr => RawHeader(attr._1, String.valueOf(attr._2))
    }
  }
}
