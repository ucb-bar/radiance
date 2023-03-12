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

  it should "whatever" in {
    test(new InflightCoalReqTable(numLanes, sourceWidth, entries)) { c =>
      // val tableEntry = new InflightCoalReqTableEntry(numLanes, sourceWidth)
      val respSourceId = 0.U
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.fromLane.poke(0.U)
      c.io.enq.bits.respSourceId.poke(respSourceId)
      c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }
      c.clock.step()
      c.io.lookup.ready.poke(true.B)
      c.io.lookupSourceId.poke(respSourceId)
      c.io.lookup.valid.expect(true.B)
      c.io.lookup.bits.expect(respSourceId)
    }
  }
}
