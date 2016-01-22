package com.ebay.myorg

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{TestProbe, TestKit}
import org.scalatest.{Matchers, FlatSpecLike}

class OrderingStageSpec extends TestKit(ActorSystem("OrderingStageSpec")) with FlatSpecLike with Matchers {

  implicit val am = ActorMaterializer()

  "OrderingStage" should "emit integers in order" in {
    val probe = TestProbe()
    implicit val myordering = Ordering[Int].reverse
    Source.fromIterator(() => Iterator(1, 0, 4, 5, 3, 2, 8, 6, 7, 9, 10)).via(new OrderingStage[Int, Int](0, (x: Int) => x + 1, (x: Int) => x)).runWith(Sink.actorRef(probe.ref, "Done"))

    probe.expectMsg(0)
    probe.expectMsg(1)
    probe.expectMsg(2)
    probe.expectMsg(3)
    probe.expectMsg(4)
    probe.expectMsg(5)
    probe.expectMsg(6)
    probe.expectMsg(7)
    probe.expectMsg(8)
    probe.expectMsg(9)
    probe.expectMsg(10)
    probe.expectMsg("Done")
  }
}
