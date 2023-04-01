package freechips.rocketchip.tilelink.coalescing

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.MultiPortQueue

class MultiPortQueueUnitTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "MultiPortQueue"

  // This is really just to figure out how MultiPortQueue works
  it should "serialize at dequeue end" in {
    test(new MultiPortQueue(UInt(4.W), 3, 1, 3, 6))
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
        c.io.enq(0).valid.poke(true.B)
        c.io.enq(0).bits.poke(11.U)
        c.io.enq(1).valid.poke(true.B)
        c.io.enq(1).bits.poke(15.U)
        c.io.enq(2).valid.poke(true.B)
        c.io.enq(2).bits.poke(7.U)
        c.io.deq(0).ready.poke(true.B)
        c.clock.step()
        // c.io.enq(0).valid.poke(false.B)
        // c.io.enq(1).valid.poke(false.B)
        for (_ <- 0 until 100) {
          c.clock.step()
        }
        // c.io.deq(0).valid.expect(false.B)
      }
  }
}

class CoalShiftQueueTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "request shift queues"

  it should "work like normal shiftqueue when no invalidate" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.deq.ready.poke(false.B)

      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x12.U)
      c.clock.step()
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x34.U)
      c.clock.step()
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x56.U)
      c.clock.step()

      c.io.enq.valid.poke(false.B)

      c.io.deq.ready.poke(true.B)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(0x12.U)
      c.clock.step()
      c.io.deq.ready.poke(true.B)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(0x34.U)
      c.clock.step()
      // enqueue in the middle
      c.io.deq.ready.poke(false.B)
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x78.U)
      c.clock.step()
      c.io.enq.valid.poke(false.B)
      c.io.deq.ready.poke(true.B)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(0x56.U)
      c.clock.step()
      c.io.deq.ready.poke(true.B)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(0x78.U)
      c.clock.step()

      // should be emptied
      c.io.deq.valid.expect(false.B)
    }
  }

  it should "work when enqueing and dequeueing simultaneously" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.invalidate.poke(0.U)

      // prepare
      c.io.deq.ready.poke(false.B)
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x12.U)
      c.clock.step()
      // enqueue and dequeue simultaneously
      c.io.deq.ready.poke(true.B)
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x34.U)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(0x12.U)
      c.clock.step()
      // dequeueing back-to-back should work without any holes in the middle
      c.io.deq.ready.poke(true.B)
      c.io.enq.valid.poke(false.B)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(0x34.U)
      c.clock.step()
      // make sure is empty
      c.io.deq.ready.poke(true.B)
      c.io.enq.valid.poke(false.B)
      c.io.deq.valid.expect(false.B)
    }
  }

  it should "invalidate head being dequeued" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.invalidate.poke(0.U)

      // prepare
      c.io.deq.ready.poke(false.B)
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x12.U)
      c.clock.step()
      c.io.deq.ready.poke(false.B)
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x34.U)
      c.clock.step()
      c.io.enq.valid.poke(false.B)

      // invalidate should work for the head just being dequeued at the same
      // cycle.  However, it should not change deq.valid right away to avoid
      // combinational cycles (see definition).
      c.io.invalidate.poke(0x1.U)
      c.io.deq.ready.poke(true.B)
      c.io.deq.valid.expect(true.B)
      c.clock.step()
      // 0x12 should have been dequeued
      c.io.invalidate.poke(0.U)
      c.io.deq.ready.poke(true.B)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(0x34.U)
    }
  }

  it should "dequeue invalidated entries by itself" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.invalidate.poke(0.U)

      // prepare
      c.io.deq.ready.poke(false.B)
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x12.U)
      c.clock.step()
      c.io.deq.ready.poke(false.B)
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x34.U)
      c.clock.step()
      c.io.deq.ready.poke(false.B)
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x56.U)
      c.clock.step()
      c.io.enq.valid.poke(false.B)

      // invalidate two entries at head
      c.io.invalidate.poke(0x3.U)
      c.clock.step()
      // 0x12 should have been dequeued now
      c.io.invalidate.poke(0x0.U)
      c.io.deq.ready.poke(false.B)
      c.clock.step()
      // 0x34 should have been dequeued now
      c.io.deq.ready.poke(true.B)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(0x56.U)
      c.clock.step()
      c.io.deq.ready.poke(true.B)
      c.io.deq.valid.expect(false.B)
    }
  }

  it should "overwrite invalidated tail when enqueuing" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.invalidate.poke(0.U)

      // prepare
      c.io.deq.ready.poke(false.B)
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x12.U)
      c.clock.step()
      // invalidate and enqueue at the tail at the same time
      c.io.invalidate.poke(0x1.U)
      c.io.deq.ready.poke(false.B)
      c.io.enq.ready.expect(true.B)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(0x34.U)
      c.clock.step()
      c.io.invalidate.poke(0x0.U)
      c.io.enq.valid.poke(false.B)
      // now should be able to dequeue immediately as tail is overwritten
      c.io.deq.ready.poke(true.B)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(0x34)
    }
  }
}

class UncoalescingUnitTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "uncoalescer"
  val numLanes = 4
  val numPerLaneReqs = 2
  val sourceWidth = 2
  // 16B coalescing size
  val coalDataWidth = 128
  val numInflightCoalRequests = 4

  it should "work" in {
    test(
      new UncoalescingUnit(
        numLanes,
        numPerLaneReqs,
        sourceWidth,
        coalDataWidth,
        numInflightCoalRequests
      )
    )
    // vcs helps with simulation time, but sometimes errors with
    // "mutation occurred during iteration" java error
    // .withAnnotations(Seq(VcsBackendAnnotation))
    { c =>
      val sourceId = 0.U
      c.io.coalReqValid.poke(true.B)
      c.io.newEntry.source.poke(sourceId)
      c.io.newEntry.lanes(0).reqs(0).valid.poke(true.B)
      c.io.newEntry.lanes(0).reqs(0).offset.poke(1.U)
      c.io.newEntry.lanes(0).reqs(0).size.poke(2.U)
      c.io.newEntry.lanes(0).reqs(1).valid.poke(true.B)
      c.io.newEntry.lanes(0).reqs(1).offset.poke(1.U)
      c.io.newEntry.lanes(0).reqs(1).size.poke(2.U)
      c.io.newEntry.lanes(2).reqs(0).valid.poke(true.B)
      c.io.newEntry.lanes(2).reqs(0).offset.poke(2.U)
      c.io.newEntry.lanes(2).reqs(0).size.poke(1.U)
      c.io.newEntry.lanes(2).reqs(1).valid.poke(true.B)
      c.io.newEntry.lanes(2).reqs(1).offset.poke(0.U)
      c.io.newEntry.lanes(2).reqs(1).size.poke(2.U)

      c.clock.step()

      c.io.coalReqValid.poke(false.B)

      c.clock.step()

      c.io.coalRespValid.poke(true.B)
      c.io.coalRespSrcId.poke(sourceId)
      val lit = (BigInt(0x0123456789abcdefL) << 64) | BigInt(0x5ca1ab1edeadbeefL)
      c.io.coalRespData.poke(lit.U)

      // table lookup is combinational at the same cycle
      c.io.uncoalResps(0)(0).valid.expect(true.B)
      c.io.uncoalResps(1)(0).valid.expect(false.B)
      c.io.uncoalResps(2)(0).valid.expect(true.B)
      c.io.uncoalResps(3)(0).valid.expect(false.B)

      c.io.uncoalResps(0)(0).bits.data.expect(0x89abcdefL.U)
      c.io.uncoalResps(0)(0).bits.source.expect(0.U)
      c.io.uncoalResps(0)(1).bits.data.expect(0x89abcdefL.U)
      c.io.uncoalResps(0)(1).bits.source.expect(0.U)
      c.io.uncoalResps(2)(0).bits.data.expect(0x5ca1ab1eL.U)
      c.io.uncoalResps(2)(0).bits.source.expect(0.U)
      c.io.uncoalResps(2)(1).bits.data.expect(0x01234567L.U)
      c.io.uncoalResps(2)(1).bits.source.expect(0.U)
    }
  }
}

class CoalInflightTableUnitTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "inflight coalesced request table"
  val numLanes = 4
  val numPerLaneReqs = 2
  val sourceWidth = 2
  val entries = 4

  val offsetBits = 4
  val sizeBits = 2

  val inflightCoalReqTableEntry =
    new InflightCoalReqTableEntry(numLanes, numPerLaneReqs, sourceWidth, offsetBits, sizeBits)

  // it should "stop enqueueing when full" in {
  //   test(new InflightCoalReqTable(numLanes, sourceWidth, entries)) { c =>
  //     // fill up the table
  //     for (i <- 0 until entries) {
  //       val sourceId = i
  //       c.io.enq.ready.expect(true.B)
  //       c.io.enq.valid.poke(true.B)
  //       c.io.enq.bits.fromLane.poke(0.U)
  //       c.io.enq.bits.respSourceId.poke(sourceId.U)
  //       c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }
  //       c.io.lookup.ready.poke(false.B)
  //       c.clock.step()
  //     }

  //     // now cannot enqueue any more
  //     c.io.enq.ready.expect(false.B)
  //     c.io.enq.valid.poke(true.B)
  //     c.io.enq.bits.fromLane.poke(0.U)
  //     c.io.enq.bits.respSourceId.poke(0.U)
  //     c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }

  //     c.clock.step()
  //     c.io.enq.ready.expect(false.B)

  //     // try to lookup all existing entries
  //     for (i <- 0 until entries) {
  //       val sourceId = i
  //       c.io.enq.valid.poke(false.B)
  //       c.io.lookup.ready.poke(true.B)
  //       c.io.lookupSourceId.poke(sourceId)
  //       c.io.lookup.valid.expect(true.B)
  //       c.io.lookup.bits.expect(sourceId)
  //       c.clock.step()
  //     }

  //     // now the table should be empty
  //     for (i <- 0 until entries) {
  //       val sourceId = i
  //       c.io.enq.valid.poke(false.B)
  //       c.io.lookup.ready.poke(true.B)
  //       c.io.lookupSourceId.poke(sourceId)
  //       c.io.lookup.valid.expect(false.B)
  //       c.clock.step()
  //     }
  //   }
  // }
  // it should "lookup matching entry" in {
  //   test(new InflightCoalReqTable(numLanes, sourceWidth, entries))
  //     .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
  //       c.reset.poke(true.B)
  //       c.clock.step(10)
  //       c.reset.poke(false.B)

  //       // enqueue one entry to not match at 0th index
  //       c.io.enq.ready.expect(true.B)
  //       c.io.enq.valid.poke(true.B)
  //       c.io.enq.bits.fromLane.poke(0.U)
  //       c.io.enq.bits.respSourceId.poke(0.U)
  //       c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }

  //       c.clock.step()

  //       val targetSourceId = 1.U
  //       c.io.enq.ready.expect(true.B)
  //       c.io.enq.valid.poke(true.B)
  //       c.io.enq.bits.fromLane.poke(0.U)
  //       c.io.enq.bits.respSourceId.poke(targetSourceId)
  //       c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }

  //       c.clock.step()

  //       c.io.lookup.ready.poke(true.B)
  //       c.io.lookupSourceId.poke(targetSourceId)
  //       c.io.lookup.valid.expect(true.B)
  //       c.io.lookup.bits.expect(targetSourceId)

  //       c.clock.step()

  //       // test if matching entry dequeues after 1 cycle
  //       c.io.lookup.ready.poke(true.B)
  //       c.io.lookupSourceId.poke(targetSourceId)
  //       c.io.lookup.valid.expect(false.B)
  //     }
  // }
  // it should "handle lookup and enqueue at the same time" in {
  //   test(new InflightCoalReqTable(numLanes, sourceWidth, entries)) { c =>
  //     // fill up the table
  //     val targetSourceId = 1.U
  //     c.io.enq.ready.expect(true.B)
  //     c.io.enq.valid.poke(true.B)
  //     c.io.enq.bits.fromLane.poke(0.U)
  //     c.io.enq.bits.respSourceId.poke(0.U)
  //     c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }
  //     c.clock.step()
  //     c.io.enq.ready.expect(true.B)
  //     c.io.enq.valid.poke(true.B)
  //     c.io.enq.bits.fromLane.poke(0.U)
  //     c.io.enq.bits.respSourceId.poke(targetSourceId)
  //     c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }
  //     c.clock.step()

  //     // do both enqueue and lookup at the same cycle
  //     val enqSourceId = 2.U
  //     c.io.enq.ready.expect(true.B)
  //     c.io.enq.valid.poke(true.B)
  //     c.io.enq.bits.fromLane.poke(0.U)
  //     c.io.enq.bits.respSourceId.poke(enqSourceId)
  //     c.io.enq.bits.reqSourceIds.foreach { id => id.poke(0.U) }
  //     c.io.lookup.ready.poke(true.B)
  //     c.io.lookupSourceId.poke(targetSourceId)

  //     c.clock.step()
  //   }
  // }
}
