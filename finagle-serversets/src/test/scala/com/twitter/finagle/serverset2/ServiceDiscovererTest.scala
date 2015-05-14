package com.twitter.finagle.serverset2

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.FunSuite

@RunWith(classOf[JUnitRunner])
class ServiceDiscovererTest extends FunSuite {
  def ep(port: Int) = Endpoint(Array(null), "localhost", port, Int.MinValue, Endpoint.Status.Alive, port.toString)

  test("ServiceDiscoverer.zipWithWeights") {
    val port1 = 80 // not bound
    val port2 = 53 // ditto
    val ents = Seq[Entry](ep(port1), ep(port2), ep(3), ep(4))
    val v1 = Vector(Seq(
      Descriptor(Selector.Host("localhost", port1), 1.1, 1),
      Descriptor(Selector.Host("localhost", port2), 1.4, 1),
      Descriptor(Selector.Member("3"), 3.1, 1)))
    val v2 = Vector(Seq(Descriptor(Selector.Member(port2.toString), 2.0, 1)))
    val vecs = Set(v1, v2)

    assert(ServiceDiscoverer.zipWithWeights(ents, vecs).toSet === Set(
      ep(port1) -> 1.1,
      ep(port2) -> 2.8,
      ep(3) -> 3.1,
      ep(4) -> 1.0))
  }
}
