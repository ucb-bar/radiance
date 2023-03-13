import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import freechips.rocketchip.tilelink._

class MyModule extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(16.W))
    val out = Output(UInt(16.W))
  })

  io.out := RegNext(io.in)
}

class BasicTest extends AnyFlatSpec with ChiselScalatestTester {
  // test class body here
  it should "do something" in {
    // test case body here
    test(new MyModule) { c =>
      // test body here
      c.io.in.poke(0.U)
      c.clock.step()
      c.io.out.expect(0.U)
      c.io.in.poke(42.U)
      c.clock.step()
      c.io.out.expect(42.U)
      println("Last output value :" + c.io.out.peek().litValue)
    }
  }
}

class CoalescingUnitTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "inflight coalesced request table"
  val numLanes = 4
  val sourceWidth = 2
  val entries = 4

  val inflightCoalReqTableEntry =
    new InflightCoalReqTableEntry(numLanes, sourceWidth)

  it should "stop enqueueing when full" in {
    test(new InflightCoalReqTable(numLanes, sourceWidth, entries)) { c =>
      // fill up the table
      for (i <- 0 until entries) {
        val sourceId = i
        c.io.enq.ready.expect(true.B)
        c.io.enq.valid.poke(true.B)
        c.io.enq.bits.fromLane.poke(0.U)
        c.io.enq.bits.respSourceId.poke(sourceId.U)
        c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }
        c.io.lookup.ready.poke(false.B)
        c.clock.step()
      }

      // now cannot enqueue any more
      c.io.enq.ready.expect(false.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.fromLane.poke(0.U)
      c.io.enq.bits.respSourceId.poke(0.U)
      c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }

      c.clock.step()
      c.io.enq.ready.expect(false.B)

      // try to lookup all existing entries
      for (i <- 0 until entries) {
        val sourceId = i
        c.io.enq.valid.poke(false.B)
        c.io.lookup.ready.poke(true.B)
        c.io.lookupSourceId.poke(sourceId)
        c.io.lookup.valid.expect(true.B)
        c.io.lookup.bits.expect(sourceId)
        c.clock.step()
      }

      // now the table should be empty
      for (i <- 0 until entries) {
        val sourceId = i
        c.io.enq.valid.poke(false.B)
        c.io.lookup.ready.poke(true.B)
        c.io.lookupSourceId.poke(sourceId)
        c.io.lookup.valid.expect(false.B)
        c.clock.step()
      }
    }
  }
  it should "lookup matching entry" in {
    test(new InflightCoalReqTable(numLanes, sourceWidth, entries))
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
        c.reset.poke(true.B)
        c.clock.step(10)
        c.reset.poke(false.B)

        // enqueue one entry to not match at 0th index
        c.io.enq.ready.expect(true.B)
        c.io.enq.valid.poke(true.B)
        c.io.enq.bits.fromLane.poke(0.U)
        c.io.enq.bits.respSourceId.poke(0.U)
        c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }

        c.clock.step()

        val targetSourceId = 1.U
        c.io.enq.ready.expect(true.B)
        c.io.enq.valid.poke(true.B)
        c.io.enq.bits.fromLane.poke(0.U)
        c.io.enq.bits.respSourceId.poke(targetSourceId)
        c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }

        c.clock.step()

        c.io.lookup.ready.poke(true.B)
        c.io.lookupSourceId.poke(targetSourceId)
        c.io.lookup.valid.expect(true.B)
        c.io.lookup.bits.expect(targetSourceId)

        c.clock.step()

        // test if matching entry dequeues after 1 cycle
        c.io.lookup.ready.poke(true.B)
        c.io.lookupSourceId.poke(targetSourceId)
        c.io.lookup.valid.expect(false.B)
      }
  }
  it should "handle lookup and enqueue at the same time" in {
    test(new InflightCoalReqTable(numLanes, sourceWidth, entries)) { c =>
      // fill up the table
      val targetSourceId = 1.U
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.fromLane.poke(0.U)
      c.io.enq.bits.respSourceId.poke(0.U)
      c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }
      c.clock.step()
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.fromLane.poke(0.U)
      c.io.enq.bits.respSourceId.poke(targetSourceId)
      c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }
      c.clock.step()

      // do both enqueue and lookup at the same cycle
      val enqSourceId = 2.U
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.fromLane.poke(0.U)
      c.io.enq.bits.respSourceId.poke(enqSourceId)
      c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }
      c.io.lookup.ready.poke(true.B)
      c.io.lookupSourceId.poke(targetSourceId)

      c.clock.step()
    }
  }
}
