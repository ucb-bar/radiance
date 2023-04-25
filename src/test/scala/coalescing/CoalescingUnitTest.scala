package freechips.rocketchip.tilelink.coalescing

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.MultiPortQueue
import freechips.rocketchip.diplomacy._
import chipsalliance.rocketchip.config.Parameters

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

class DummyCoalescingUnitTB(implicit p: Parameters) extends LazyModule {
  val cpuNodes = Seq.tabulate(testConfig.NUM_LANES) { _ =>
    TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLClientParameters(
      name = "processor-nodes",
      sourceId = IdRange(0, testConfig.NUM_OLD_IDS),
      requestFifo = true,
      visibility = Seq(AddressSet(0x0, 0xffffff))))))) // 24 bit address space (TODO probably use testConfig)
  }

  val mitm = Seq.tabulate(testConfig.NUM_LANES) {_ => TLIdentityNode()}

  val device = new SimpleDevice("dummy", Seq("dummy"))
  val beatBytes = 1 << testConfig.DATA_BUS_SIZE // 256 bit bus
  val l2Nodes = Seq.tabulate(5) { _ =>
    TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLManagerParameters(
      address = Seq(AddressSet(0x0, 0xffffff)), // should be matching cpuNode
      resources = device.reg,
      regionType = RegionType.UNCACHED,
      executable = true,
      supportsArithmetic = TransferSizes(1, beatBytes),
      supportsLogical = TransferSizes(1, beatBytes),
      supportsGet = TransferSizes(1, beatBytes),
      supportsPutFull = TransferSizes(1, beatBytes),
      supportsPutPartial = TransferSizes(1, beatBytes),
      supportsHint = TransferSizes(1, beatBytes),
      fifoId = Some(0))), beatBytes)))
  }

  val dut = LazyModule(new CoalescingUnit(testConfig))

  lazy val module = new DummyCoalescingUnitTBImp(this)
}

class DummyCoalescingUnitTBImp(outer: DummyCoalescingUnitTB) extends LazyModuleImp(outer) {
  val mitmNodesImp = outer.mitm
  val coal = outer.dut
}

class CoalescerUnitTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "multi- and mono-coalescers"

  it should "coalesce fully consecutive accesses at size 4, only once" in {
    implicit val p: Parameters = Parameters.empty

    val tb = LazyModule(new DummyCoalescingUnitTB())
//    val outer = LazyModule(new CoalescingUnit(testConfig))

    val coal = tb.dut
    tb.cpuNodes.zip(tb.mitm).foreach { case (a, b) => b := a }
    tb.mitm.foreach(coal.node := _)
    tb.l2Nodes.foreach(_ := coal.node)

    test(tb.module) { c =>
//      val nodes = c.cpuNodesImp.map(_.out.head._1)
//      val nodes = c.coal.node.in.map(_._1)
      val nodes = c.mitmNodesImp.map(_.in.head._1)

      def pokeA(nodes: Seq[TLBundle], idx: Int, op: Int, size: Int, source: Int, addr: Int, mask: Int, data: Int): Unit = {
        val node = nodes(idx)
//        node.a.ready.expect(true.B)
        node.a.bits.opcode.poke(if (op == 1) TLMessages.PutFullData else TLMessages.Get)
        node.a.bits.param.poke(0.U)
        node.a.bits.size.poke(size.U)
        node.a.bits.source.poke(source.U)
        node.a.bits.address.poke(addr.U)
        node.a.bits.mask.poke(mask.U)
        node.a.bits.data.poke(data.U)
        node.a.bits.corrupt.poke(false.B)
//        node.a.valid.poke(true.B)
      }

      def unsetA(): Unit = {
        nodes.foreach { node =>
          node.a.bits.poke(DontCare.asTypeOf(node.a.bits))
          node.a.valid.poke(false.B)
        }
      }

      pokeA(nodes, idx=0, op=1, size=2, source=0, addr=0x10, mask=0xf, data=0x1111)
      pokeA(nodes, idx=1, op=1, size=2, source=1, addr=0x14, mask=0xf, data=0x2222)
      pokeA(nodes, idx=2, op=1, size=2, source=2, addr=0x18, mask=0xf, data=0x3333)
      pokeA(nodes, idx=3, op=1, size=2, source=3, addr=0x1c, mask=0xf, data=0x4444)

      c.clock.step()

      unsetA()

      c.clock.step()
      c.clock.step()
    }
  }

  it should "coalesce strided accesses at size 6" in {

  }

  it should "coalesce the coalescable chunk and leave 2 uncoalescable requests" in {

  }

  it should "not touch uncoalescable requests" in {

  }

  it should "allow temporal coalescing when depth >=2" in {

  }

  it should "select the most coverage mono-coalescer" in {

  }

  it should "resort to the backup policy when coverage is below average" in {

  }
}

class CoalShiftQueueTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "request shift queues"

  it should "work like normal shiftqueue when no invalidate" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.queue.deq.ready.poke(false.B)

      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x12.U)
      c.clock.step()
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x34.U)
      c.clock.step()
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x56.U)
      c.clock.step()

      c.io.queue.enq.valid.poke(false.B)

      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x12.U)
      c.clock.step()
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x34.U)
      c.clock.step()
      // enqueue in the middle
      c.io.queue.deq.ready.poke(false.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x78.U)
      c.clock.step()
      c.io.queue.enq.valid.poke(false.B)
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x56.U)
      c.clock.step()
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x78.U)
      c.clock.step()

      // should be emptied
      c.io.queue.deq.valid.expect(false.B)
    }
  }

  it should "work when enqueing and dequeueing simultaneously" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.invalidate.valid.poke(false.B)

      // prepare
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x12.U)
      c.clock.step()
      // enqueue and dequeue simultaneously
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x34.U)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x12.U)
      c.clock.step()
      // dequeueing back-to-back should work without any holes in the middle
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.enq.valid.poke(false.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x34.U)
      c.clock.step()
      // make sure is empty
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.enq.valid.poke(false.B)
      c.io.queue.deq.valid.expect(false.B)
    }
  }

  it should "work when enqueing and dequeueing simultaneously to a full queue" in {
    test(new CoalShiftQueue(UInt(8.W), 1)) { c =>
      c.io.invalidate.valid.poke(false.B)

      // prepare
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x12.U)
      c.clock.step()
      // enqueue and dequeue simultaneously
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x34.U)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x12.U)
      c.clock.step()
      // enqueue and dequeue simultaneously once more
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x56.U)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x34.U)
      c.clock.step()
      // dequeueing back-to-back should work without any holes in the middle
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.enq.valid.poke(false.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x56.U)
      c.clock.step()
      // make sure is empty
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.enq.valid.poke(false.B)
      c.io.queue.deq.valid.expect(false.B)
    }
  }

  it should "invalidate head being dequeued" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.invalidate.valid.poke(false.B)

      // prepare
      c.io.queue.deq.ready.poke(false.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x12.U)
      c.clock.step()
      c.io.queue.deq.ready.poke(false.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x34.U)
      c.clock.step()
      c.io.queue.enq.valid.poke(false.B)

      // invalidate should work for the head just being dequeued at the same
      // cycle.  However, it should not change deq.valid right away to avoid
      // combinational cycles (see definition).
      c.io.invalidate.valid.poke(true.B)
      c.io.invalidate.bits.poke(0x1.U)
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.clock.step()
      // 0x12 should have been dequeued
      c.io.invalidate.valid.poke(false.B)
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x34.U)
    }
  }

  it should "dequeue invalidated entries by itself" in {
    test(new CoalShiftQueue(gen = UInt(8.W), entries = 4)) { c =>
      c.io.invalidate.valid.poke(false.B)

      // prepare
      c.io.queue.deq.ready.poke(false.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x12.U)
      c.clock.step()
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x34.U)
      c.clock.step()
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x56.U)
      c.clock.step()
      c.io.queue.enq.valid.poke(false.B)

      // invalidate two entries at head
      c.io.invalidate.valid.poke(true.B)
      c.io.invalidate.bits.poke(0x3.U)
      // [ 0x56 | 0x34(inv) | 0x12(inv) ]
      c.clock.step()
      // [ 0x56 | 0x34(inv) ]
      c.io.invalidate.valid.poke(false.B)
      c.io.queue.deq.ready.poke(false.B)
      c.clock.step()
      // [ 0x56 ]
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x56.U)
      c.clock.step()
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(false.B)
    }
  }

  it should "overwrite invalidated tail when enqueuing" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.invalidate.valid.poke(false.B)
      c.io.invalidate.bits.poke(0.U)

      // prepare
      c.io.queue.deq.ready.poke(false.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x12.U)
      c.clock.step()
      // invalidate and enqueue at the tail at the same time
      c.io.invalidate.valid.poke(true.B)
      c.io.invalidate.bits.poke(0x1.U)
      c.io.queue.deq.ready.poke(false.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x34.U)
      c.clock.step()
      c.io.invalidate.valid.poke(false.B)
      c.io.queue.enq.valid.poke(false.B)
      // now should be able to dequeue immediately as tail is overwritten
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x34)
    }
  }
}

object testConfig extends CoalescerConfig(
  MAX_SIZE = 5,       // maximum coalesced size
  DEPTH = 2,          // request window per lane
  WAIT_TIMEOUT = 8,   // max cycles to wait before forced fifo dequeue, per lane
  ADDR_WIDTH = 24,    // assume <= 32
  DATA_BUS_SIZE = 5,  // 2^5=32 bytes, 256 bit bus
  NUM_LANES = 4,
  // WATERMARK = 2,      // minimum buffer occupancy to start coalescing
  WORD_SIZE = 4,      // 32-bit system
  WORD_WIDTH = 2,     // log(WORD_SIZE)
  NUM_OLD_IDS = 16,   // num of outstanding requests per lane, from processor
  NUM_NEW_IDS = 4,    // num of outstanding coalesced requests
  COAL_SIZES = Seq(4, 5),
  SizeEnum = DefaultInFlightTableSizeEnum
)

class UncoalescingUnitTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "uncoalescer"
  val numLanes = 4
  val numPerLaneReqs = 2
  val sourceWidth = 2
  val sizeWidth = 2
  // 16B coalescing size
  val coalDataWidth = 128
  val numInflightCoalRequests = 4

  it should "work" in {
    test(new UncoalescingUnit(testConfig))
    // vcs helps with simulation time, but sometimes errors with
    // "mutation occurred during iteration" java error
    // .withAnnotations(Seq(VcsBackendAnnotation))
    { c =>
      val sourceId = 0.U
      val four = c.io.newEntry.sizeEnumT.FOUR
      c.io.coalReqValid.poke(true.B)
      c.io.newEntry.source.poke(sourceId)
      c.io.newEntry.lanes(0).reqs(0).valid.poke(true.B)
      c.io.newEntry.lanes(0).reqs(0).source.poke(1.U)
      c.io.newEntry.lanes(0).reqs(0).offset.poke(1.U)
      c.io.newEntry.lanes(0).reqs(0).sizeEnum.poke(four)
      c.io.newEntry.lanes(0).reqs(1).valid.poke(true.B)
      c.io.newEntry.lanes(0).reqs(1).source.poke(2.U)
      c.io.newEntry.lanes(0).reqs(1).offset.poke(0.U)
      c.io.newEntry.lanes(0).reqs(1).sizeEnum.poke(four)
      c.io.newEntry.lanes(1).reqs(0).valid.poke(false.B)
      c.io.newEntry.lanes(2).reqs(0).valid.poke(true.B)
      c.io.newEntry.lanes(2).reqs(0).source.poke(2.U)
      c.io.newEntry.lanes(2).reqs(0).offset.poke(2.U)
      c.io.newEntry.lanes(2).reqs(0).sizeEnum.poke(four)
      c.io.newEntry.lanes(2).reqs(1).valid.poke(true.B)
      c.io.newEntry.lanes(2).reqs(1).source.poke(2.U)
      c.io.newEntry.lanes(2).reqs(1).offset.poke(3.U)
      c.io.newEntry.lanes(2).reqs(1).sizeEnum.poke(four)
      c.io.newEntry.lanes(3).reqs(0).valid.poke(false.B)

      c.clock.step()

      c.io.coalReqValid.poke(false.B)

      c.clock.step()

      c.io.coalResp.valid.poke(true.B)
      c.io.coalResp.bits.source.poke(sourceId)
      val lit = (BigInt(0x0123456789abcdefL) << 64) | BigInt(0x5ca1ab1edeadbeefL)
      // val lit = BigInt(0x0123456789abcdefL)
      c.io.coalResp.bits.data.poke(lit.U)

      // table lookup is combinational at the same cycle
      c.io.uncoalResps(0)(0).valid.expect(true.B)
      c.io.uncoalResps(1)(0).valid.expect(false.B)
      c.io.uncoalResps(2)(0).valid.expect(true.B)
      c.io.uncoalResps(3)(0).valid.expect(false.B)

      // offset is counting from LSB
      c.io.uncoalResps(0)(0).bits.data.expect(0x5ca1ab1eL.U)
      c.io.uncoalResps(0)(0).bits.source.expect(1.U)
      c.io.uncoalResps(0)(1).bits.data.expect(0xdeadbeefL.U)
      c.io.uncoalResps(0)(1).bits.source.expect(2.U)
      c.io.uncoalResps(2)(0).bits.data.expect(0x89abcdefL.U)
      c.io.uncoalResps(2)(0).bits.source.expect(2.U)
      c.io.uncoalResps(2)(1).bits.data.expect(0x01234567L.U)
      c.io.uncoalResps(2)(1).bits.source.expect(2.U)
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
    new InflightCoalReqTableEntry(
      numLanes,
      numPerLaneReqs,
      sourceWidth,
      offsetBits,
      testConfig.SizeEnum
    )

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
