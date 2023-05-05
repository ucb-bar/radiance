package freechips.rocketchip.tilelink.coalescing

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.MultiPortQueue
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import chisel3.util.{DecoupledIO, Valid}
import chisel3.util.experimental.BoringUtils

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
  val cpuNodes = Seq.tabulate(testConfig.numLanes) { _ =>
    TLClientNode(
      Seq(
        TLMasterPortParameters.v1(
          Seq(
            TLClientParameters(
              name = "processor-nodes",
              sourceId = IdRange(0, testConfig.numOldSrcIds),
              requestFifo = true,
              visibility = Seq(AddressSet(0x0, 0xffffff))
            )
          )
        )
      )
    ) // 24 bit address space (TODO probably use testConfig)
  }

  val device = new SimpleDevice("dummy", Seq("dummy"))
  val beatBytes = 1 << testConfig.dataBusWidth // 256 bit bus
  val l2Nodes = Seq.tabulate(5) { _ =>
    TLManagerNode(
      Seq(
        TLSlavePortParameters.v1(
          Seq(
            TLManagerParameters(
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
              fifoId = Some(0)
            )
          ),
          beatBytes
        )
      )
    )
  }

  val dut = LazyModule(new CoalescingUnit(testConfig))

  cpuNodes.foreach(dut.cpuNode := _)
  l2Nodes.foreach(_ := dut.aggregateNode)

  lazy val module = new DummyCoalescingUnitTBImp(this)
}

class DummyCoalescingUnitTBImp(outer: DummyCoalescingUnitTB) extends LazyModuleImp(outer) {
  val coal = outer.dut
  // FIXME: these need to be separate variables because of implicit naming in makeIOs
  // there has to be a better way
  val coalIO0 = outer.cpuNodes(0).makeIOs()
  val coalIO1 = outer.cpuNodes(1).makeIOs()
  val coalIO2 = outer.cpuNodes(2).makeIOs()
  val coalIO3 = outer.cpuNodes(3).makeIOs()
  val coalIOs = Seq(coalIO0, coalIO1, coalIO2, coalIO3)

//  val coalMasterNode = coal.coalescerNode.makeIOs()

  private val reqQueues = coal.module.reqQueues
  private val coalescer = coal.module.coalescer

  // workaround for peeking internal signals as outlined in
  // https://github.com/ucb-bar/chiseltest/issues/17

  private val peekIn = Seq(
    reqQueues.io.queue.enq.map(_.ready),
    reqQueues.io.queue.enq.map(_.bits),
    reqQueues.io.queue.enq.map(_.valid),
    reqQueues.io.queue.deq.map(_.bits),
    reqQueues.io.queue.deq.map(_.valid),
    coalescer.io.coalReq.ready,
    coalescer.io.coalReq.bits,
    coalescer.io.coalReq.valid,
    coalescer.io.invalidate,
  )

  val reqQueueEnqReady =  peekIn(0).asInstanceOf[Seq[Bool]].map(x => IO(x.cloneType))
  val reqQueueEnqBits =   peekIn(1).asInstanceOf[Seq[ReqQueueEntry]].map(x => IO(x.cloneType))
  val reqQueueEnqValid =  peekIn(2).asInstanceOf[Seq[Bool]].map(x => IO(x.cloneType))
  val reqQueueDeqBits =   peekIn(3).asInstanceOf[Seq[ReqQueueEntry]].map(x => IO(Output(x.cloneType)))
  val reqQueueDeqValid =  peekIn(4).asInstanceOf[Seq[Bool]].map(x => IO(Output(x.cloneType)))
  val coalReqReady =      IO(Output(peekIn(5).asInstanceOf[Bool].cloneType))
  val coalReqBits =       IO(Output(peekIn(6).asInstanceOf[ReqQueueEntry].cloneType))
  val coalReqValid =      IO(Output(peekIn(7).asInstanceOf[Bool].cloneType))
  val coalInvalidate =    IO(Output(peekIn(8).asInstanceOf[Valid[Vec[UInt]]].cloneType))

  private val peekOut = Seq(
    reqQueueEnqReady, reqQueueEnqBits, reqQueueEnqValid,
    reqQueueDeqBits, reqQueueDeqValid,
    coalReqReady, coalReqBits, coalReqValid, coalInvalidate,
  )

  (peekIn zip peekOut).foreach {
    case (inner: IndexedSeq[Data], outer: Seq[Data]) =>
      (inner zip outer).foreach { case (i, o) =>
        BoringUtils.bore(i, Seq(o))
      }
    case (inner: Data, outer: Data) =>
      BoringUtils.bore(inner, Seq(outer))
    case _ =>
      assert(false, "boring between different data types")
  }

  private val pokeIn = Seq(
    reqQueues.io.queue.deq.map(_.ready),
//    coalescer.io.coalReq.ready
  )

  val reqQueueDeqReady = pokeIn(0).asInstanceOf[Seq[Bool]].map(x => IO(x.cloneType))

  private val pokeOut = Seq(
    reqQueueDeqReady
  )

  // TODO: doesn't work yet
  (pokeIn zip pokeOut).foreach {
    case (inner: IndexedSeq[Data], outer: Seq[Data]) =>
      (inner zip outer).foreach { case (i, o) =>
        BoringUtils.bore(i, Seq(o))
      }
    case (inner: Data, outer: Data) =>
      BoringUtils.bore(inner, Seq(outer))
    case _ =>
      assert(false, "boring between different data types")
  }
}

object testConfig extends CoalescerConfig(
  enable = true,
  numLanes = 4,
  queueDepth = 1,
  waitTimeout = 8,
  addressWidth = 24,
  dataBusWidth = 5,
  // watermark = 2,
  wordSizeInBytes = 4,
  wordWidth = 2,
  numOldSrcIds = 16,
  numNewSrcIds = 4,
  respQueueDepth = 4,
  coalLogSizes = Seq(4, 5),
  sizeEnum = DefaultInFlightTableSizeEnum,
  arbiterOutputs = 4
)

class CoalescerUnitTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "multi- and mono-coalescers"

  implicit val p: Parameters = Parameters.empty

  def makeTb() = {
    val tb = LazyModule(new DummyCoalescingUnitTB())
    // val outer = LazyModule(new CoalescingUnit(testConfig))

    val coal = tb.dut
    tb.cpuNodes.foreach(coal.node := _)
    tb.l2Nodes.foreach(_ := coal.node)
    tb
  }

  def pokeA(
      nodes: Seq[TLBundle],
      idx: Int, op: Int, size: Int, source: Int, addr: Int, mask: Int, data: Int,
      valid: Boolean = true,
  ): Unit = {
    val node = nodes(idx)
//    node.a.ready.expect(true.B) // FIXME: this fails currently
    node.a.bits.opcode.poke(if (op == 1) TLMessages.PutFullData else TLMessages.Get)
    node.a.bits.param.poke(0.U)
    node.a.bits.size.poke(size.U)
    node.a.bits.source.poke(source.U)
    node.a.bits.address.poke(addr.U)
    node.a.bits.mask.poke(mask.U)
    node.a.bits.data.poke(data.U)
    node.a.bits.corrupt.poke(false.B)
    node.a.valid.poke(valid.B)
  }

  def unsetA(nodes: Seq[TLBundle]): Unit = {
    nodes.foreach { node =>
      node.a.valid.poke(false.B)
    }
  }

  def expectVec[T <: Data](vec: Seq[T], value: Seq[T]): Unit = {
    (vec zip value).foreach { case (a, b) => a.expect(b) }
  }

  it should "coalesce fully consecutive accesses at size 4, only once" in {
    test(LazyModule(new DummyCoalescingUnitTB()).module)
    .withAnnotations(Seq(VcsBackendAnnotation, WriteFsdbAnnotation))
    { c =>
      println(s"coalIO length = ${c.coalIOs(0).length}")
      val nodes = c.coalIOs.map(_.head)
      // TODO: this doesn't work
//      c.coalMasterNode.head.a.ready.poke(true.B)

      c.reqQueueEnqReady.foreach(_.expect(true.B))
      pokeA(nodes, idx = 0, op = 1, size = 2, source = 0, addr = 0x10, mask = 0xf, data = 0x1111)
      pokeA(nodes, idx = 1, op = 1, size = 2, source = 0, addr = 0x14, mask = 0xf, data = 0x2222)
      pokeA(nodes, idx = 2, op = 1, size = 2, source = 0, addr = 0x18, mask = 0xf, data = 0x3333)
      pokeA(nodes, idx = 3, op = 1, size = 2, source = 0, addr = 0x1c, mask = 0xf, data = 0x4444)
      expectVec(c.reqQueueEnqBits.map(_.data), Seq(0x1111.U, 0x2222.U, 0x3333.U, 0x4444.U))
      c.clock.step()

      unsetA(nodes)
      c.reqQueueDeqValid.foreach(_.expect(false.B))

      c.coalReqValid.expect(true.B)
      c.coalReqBits.address.expect(0x10.U)
      c.coalReqBits.data.expect(BigInt("4444000033330000222200001111", 16) << 128)
      c.coalReqBits.mask.expect(0xffff0000L)
      c.coalReqBits.size.expect(4.U)
      c.coalReqBits.op.expect(1.U)

//      c.coalReqReady.expect(true.B)
      c.reqQueueEnqReady.foreach(_.expect(true.B))
      pokeA(nodes, idx = 0, op = 1, size = 2, source = 1, addr = 0xf20, mask = 0xf, data = 0x5555)
      pokeA(nodes, idx = 1, op = 1, size = 2, source = 1, addr = 0xf24, mask = 0xf, data = 0x6666, valid = false)
      pokeA(nodes, idx = 2, op = 1, size = 2, source = 1, addr = 0xf28, mask = 0xf, data = 0x7777)
      pokeA(nodes, idx = 3, op = 1, size = 2, source = 1, addr = 0xf2c, mask = 0xf, data = 0x8888, valid = false)
      c.clock.step()

      c.coalReqValid.expect(true.B)
      c.coalReqBits.address.expect(0xf20.U)
      c.coalReqBits.data.expect(BigInt("77770000000000005555", 16)) // technically these can be dontcare's
      c.coalReqBits.mask.expect(0x0000ffff)
      c.coalReqBits.size.expect(4.U)
      c.coalReqBits.op.expect(1.U)

      c.clock.step()
      c.clock.step()
    }
  }

  it should "coalesce identical addresses (stride of 0)" in {
    test(makeTb().module)
    .withAnnotations(Seq(VcsBackendAnnotation))
    { c =>
      println(s"coalIO length = ${c.coalIOs(0).length}")
      val nodes = c.coalIOs.map(_.head)

      pokeA(nodes, idx = 0, op = 1, size = 2, source = 0, addr = 0x18, mask = 0xf, data = 0x1111)
      pokeA(nodes, idx = 1, op = 1, size = 2, source = 0, addr = 0x18, mask = 0xf, data = 0x2222)
      pokeA(nodes, idx = 2, op = 1, size = 2, source = 0, addr = 0x18, mask = 0xf, data = 0x3333)
      pokeA(nodes, idx = 3, op = 1, size = 2, source = 0, addr = 0x18, mask = 0xf, data = 0x4444)

      c.clock.step()

      unsetA(nodes)

      c.clock.step()
      c.clock.step()
    }
  }

//  it should "coalesce strided accesses at size 6" in {}
//
//  it should "coalesce the coalescable chunk and leave 2 uncoalescable requests" in {}
//
//  it should "not touch uncoalescable requests" in {}
//
//  it should "allow temporal coalescing when depth >=2" in {}
//
//  it should "select the most coverage mono-coalescer" in {}
//
//  it should "resort to the backup policy when coverage is below average" in {}
}

class CoalShiftQueueTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "request shift queues"

  def attemptEnqueue(c: CoalShiftQueue[UInt], bits: Seq[UInt], valids: Seq[Bool]): Unit = {
    ((c.io.queue.enq zip bits) zip valids).foreach { case ((enq, ent), valid) =>
      enq.ready.expect(true.B)
      enq.valid.poke(valid)
      enq.bits.poke(ent)
    }
    c.clock.step()
  }

  def expectDequeue(c: CoalShiftQueue[UInt], bits: Seq[UInt], valids: Seq[Bool]): Unit = {
    ((c.io.queue.deq zip bits) zip valids).foreach { case ((deq, ent), valid) =>
      deq.valid.expect(valid)
      deq.bits.expect(ent)
    }
  }

  def pokeVec[T <: Data](vec: Seq[T], value: Seq[T]): Unit = {
    (vec zip value).foreach { case (a, b) => a.poke(b) }
  }

  it should "work like normal shiftqueue when no invalidate" in {

    test(new CoalShiftQueue(UInt(8.W),4, testConfig)) { c =>
      c.io.coalescable.foreach(_.poke(true.B))
      c.io.queue.deq.foreach(_.ready.poke(false.B))

      attemptEnqueue(c, Seq.fill(4)(1.U), Seq.fill(4)(true.B))
      attemptEnqueue(c, Seq.fill(4)(2.U), Seq(true.B, false.B, false.B, false.B)) // should remain synchronous
      attemptEnqueue(c, Seq.fill(4)(3.U), Seq.fill(4)(true.B))

      c.io.queue.enq.foreach(_.valid.poke(false.B))
      c.io.queue.enq.foreach(_.ready.expect(true.B))
      // check if head is the first enqueued item
      expectDequeue(c, Seq.fill(4)(1.U), Seq.fill(4)(false.B))
      c.clock.step()

      c.io.queue.deq.foreach(_.ready.poke(true.B))
      // should not dequeue because all are coalescable
      expectDequeue(c, Seq.fill(4)(1.U), Seq.fill(4)(false.B))
      c.clock.step()

      pokeVec(c.io.coalescable, Seq(false.B, false.B, false.B, true.B))
      // first 3 items should be valid now
      expectDequeue(c, Seq.fill(4)(1.U), Seq(true.B, true.B, true.B, false.B))
      // only dequeue first item - 4th item should not be dequeued since not valid
      pokeVec(c.io.queue.deq.map(_.ready), Seq(true.B, false.B, false.B, true.B))
      c.clock.step()

      // first item should turn invalid
      c.io.coalescable.foreach(_.poke(false.B))
      expectDequeue(c, Seq.fill(4)(1.U), Seq(false.B, true.B, true.B, true.B))
      // now dequeue everything else in the first line
      c.io.queue.deq.foreach(_.ready.poke(true.B))
      c.clock.step()

      // all dequeued, none valid this cycle
      expectDequeue(c, Seq.fill(4)(1.U), Seq.fill(4)(false.B))
      c.clock.step()

      // shifted last cycle
      c.io.coalescable.foreach(_.poke(false.B))
      c.io.queue.deq.foreach(_.ready.poke(true.B))
      expectDequeue(c, Seq.fill(4)(2.U), Seq(true.B, false.B, false.B, false.B))
      c.clock.step()

      expectDequeue(c, Seq.fill(4)(2.U), Seq(false.B, false.B, false.B, false.B))
      c.clock.step()

      pokeVec(c.io.coalescable, Seq(true.B, false.B, true.B, true.B))
      expectDequeue(c, Seq.fill(4)(3.U), Seq(false.B, true.B, false.B, false.B))
      c.clock.step()

      c.io.coalescable.foreach(_.poke(false.B))
      expectDequeue(c, Seq.fill(4)(3.U), Seq(true.B, false.B, true.B, true.B))
      c.clock.step()

      // empty
      expectDequeue(c, Seq.fill(4)(3.U), Seq.fill(4)(false.B))

      // now enqueue back to full & test back pressure
      c.io.queue.deq.foreach(_.ready.poke(false.B))
      attemptEnqueue(c, Seq.fill(4)(1.U), Seq.fill(4)(true.B))
      pokeVec(c.io.coalescable, Seq(true.B, true.B, true.B, true.B))
      attemptEnqueue(c, Seq.fill(4)(2.U), Seq.fill(4)(true.B))
      attemptEnqueue(c, Seq.fill(4)(3.U), Seq.fill(4)(true.B))
      attemptEnqueue(c, Seq.fill(4)(4.U), Seq.fill(4)(true.B))

      // check full
      c.io.queue.enq.foreach(_.ready.expect(false.B))
      c.clock.step()

      // now indicate the next cycle will dequeue everything
      c.io.queue.deq.foreach(_.ready.poke(true.B))
      c.io.coalescable.foreach(_.poke(false.B))
      c.clock.step()

      // should still be full, but allow enqueue
      c.io.coalescable.foreach(_.poke(true.B))
      c.io.queue.enq.foreach(_.ready.expect(false.B)) // check full
      c.io.coalescable.foreach(_.poke(false.B))
      attemptEnqueue(c, Seq.fill(4)(5.U), Seq.fill(4)(true.B))

      expectDequeue(c, Seq.fill(4)(2.U), Seq.fill(4)(true.B))
      c.clock.step()

      attemptEnqueue(c, Seq.fill(4)(6.U), Seq.fill(4)(true.B))
    }
  }

  it should "work when enqueing and dequeueing simultaneously" in {
    test(new CoalShiftQueue(UInt(8.W), 4, testConfig)) { c =>
      c.io.invalidate.valid.poke(false.B)

      c.io.coalescable.foreach(_.poke(true.B))
      c.io.queue.deq.foreach(_.ready.poke(false.B))

      attemptEnqueue(c, Seq.fill(4)(1.U), Seq.fill(4)(true.B))

      // mark for dequeue
      c.io.coalescable.foreach(_.poke(false.B))
      c.io.queue.deq.foreach(_.ready.poke(true.B))
      expectDequeue(c, Seq.fill(4)(1.U), Seq.fill(4)(true.B))
      attemptEnqueue(c, Seq.fill(4)(2.U), Seq.fill(4)(true.B))

      expectDequeue(c, Seq.fill(4)(1.U), Seq.fill(4)(false.B))
      attemptEnqueue(c, Seq.fill(4)(3.U), Seq.fill(4)(true.B))

      expectDequeue(c, Seq.fill(4)(2.U), Seq.fill(4)(true.B))
      attemptEnqueue(c, Seq.fill(4)(4.U), Seq.fill(4)(true.B))

      expectDequeue(c, Seq.fill(4)(2.U), Seq.fill(4)(false.B))
      attemptEnqueue(c, Seq.fill(4)(5.U), Seq.fill(4)(true.B))

      expectDequeue(c, Seq.fill(4)(3.U), Seq.fill(4)(true.B))
      c.clock.step()
      expectDequeue(c, Seq.fill(4)(3.U), Seq.fill(4)(false.B))
      c.clock.step()
      expectDequeue(c, Seq.fill(4)(4.U), Seq.fill(4)(true.B))
      c.clock.step()
      expectDequeue(c, Seq.fill(4)(4.U), Seq.fill(4)(false.B))
      c.clock.step()
      expectDequeue(c, Seq.fill(4)(5.U), Seq.fill(4)(true.B))
      c.clock.step()
      expectDequeue(c, Seq.fill(4)(5.U), Seq.fill(4)(false.B))
      c.clock.step()
    }
  }
/*
  it should "work when enqueing and dequeueing simultaneously to a depth=1 queue" in {
    test(new CoalShiftQueue(UInt(8.W), 1)) { c =>
      c.io.invalidate.valid.poke(false.B)
      c.io.allowShift.poke(true.B)

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

  it should "work when invalidating and enqueueing to a depth=1 queue" in {
    test(new CoalShiftQueue(UInt(8.W), 1)) { c =>
      c.io.invalidate.valid.poke(false.B)
      c.io.allowShift.poke(true.B)
      // no dequeueing
      c.io.queue.deq.ready.poke(false.B)

      // prepare
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x12.U)
      c.clock.step()
      // invalidate, but don't allow shift
      c.io.allowShift.poke(false.B)
      c.io.invalidate.valid.poke(true.B)
      c.io.invalidate.bits.poke(0x1.U)
      // TODO: we might be able to enqueue to a full depth=1 queue whose only
      // entry just got invalidated, so that enq.ready is true here, but
      // it is a niche case
      c.io.queue.enq.ready.expect(false.B)
      c.clock.step()
      // now try enqueueing now that we have space
      c.io.allowShift.poke(true.B)
      c.io.invalidate.valid.poke(false.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x34.U)
      c.io.queue.deq.valid.expect(false.B)
      c.clock.step()
      // see if it comes out right next cycle
      c.io.queue.enq.valid.poke(false.B)
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x34.U)
    }
  }

  it should "invalidate head that is also being dequeued" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.invalidate.valid.poke(false.B)
      c.io.allowShift.poke(true.B)

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
      // cycle
      c.io.invalidate.valid.poke(true.B)
      c.io.invalidate.bits.poke(0x1.U)
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(false.B)
      c.clock.step()
      // 0x12 should have been dequeued
      c.io.invalidate.valid.poke(false.B)
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x34.U)
    }
  }

  it should "dequeue invalidated head on its own when allowShift" in {
    test(new CoalShiftQueue(gen = UInt(8.W), entries = 4)) { c =>
      c.io.invalidate.valid.poke(false.B)

      c.io.allowShift.poke(true.B)

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
      c.io.queue.deq.ready.poke(false.B)
      // [ 0x56 | 0x34(inv) | 0x12(inv) ]
      c.clock.step()
      //             [ 0x56 | 0x34(inv) ]
      c.io.invalidate.valid.poke(false.B)
      c.io.queue.deq.ready.poke(false.B)
      c.clock.step()
      //                         [ 0x56 ]
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x56.U)
      c.clock.step()
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(false.B)
      c.clock.step()

      // do one more enqueue-then-dequeue to see if used bit was properly cleared
      c.io.queue.deq.ready.poke(false.B)
      c.io.queue.enq.ready.expect(true.B)
      c.io.queue.enq.valid.poke(true.B)
      c.io.queue.enq.bits.poke(0x78.U)
      c.clock.step()
      // should dequeue right away
      c.io.queue.enq.valid.poke(false.B)
      c.io.queue.deq.ready.poke(true.B)
      c.io.queue.deq.valid.expect(true.B)
      c.io.queue.deq.bits.expect(0x78.U)
    }
  }

  it should "overwrite invalidated tail when enqueuing" in {
    test(new CoalShiftQueue(UInt(8.W), 4)) { c =>
      c.io.invalidate.valid.poke(false.B)
      c.io.invalidate.bits.poke(0.U)
      c.io.allowShift.poke(true.B)

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
  }*/
}

object uncoalescerTestConfig extends CoalescerConfig(
  enable = true,
  numLanes = 4,
  queueDepth = 2,
  waitTimeout = 8,
  addressWidth = 24,
  dataBusWidth = 5,
  // watermark = 2,
  wordSizeInBytes = 4,
  wordWidth = 2,
  numOldSrcIds = 16,
  numNewSrcIds = 4,
  respQueueDepth = 4,
  coalLogSizes = Seq(4),
  sizeEnum = DefaultInFlightTableSizeEnum,
  arbiterOutputs = 4
)

class UncoalescerUnitTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "uncoalescer"
  val numLanes = 4
  val numPerLaneReqs = 2
  val sourceWidth = 2
  val sizeWidth = 2
  // 16B coalescing size
  val coalDataWidth = 128
  val numInflightCoalRequests = 4

  it should "work in general case" in {
    test(new Uncoalescer(uncoalescerTestConfig))
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
      c.io.newEntry.lanes(0).reqs(1).offset.poke(1.U) // same offset to different lanes
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
      c.io.uncoalResps(0)(1).bits.data.expect(0x5ca1ab1eL.U)
      c.io.uncoalResps(0)(1).bits.source.expect(2.U)
      c.io.uncoalResps(2)(0).bits.data.expect(0x89abcdefL.U)
      c.io.uncoalResps(2)(0).bits.source.expect(2.U)
      c.io.uncoalResps(2)(1).bits.data.expect(0x01234567L.U)
      c.io.uncoalResps(2)(1).bits.source.expect(2.U)
    }
  }

  it should "uncoalesce when coalesced to the same word offset" in {
    test(new Uncoalescer(uncoalescerTestConfig))
    // .withAnnotations(Seq(VcsBackendAnnotation))
    { c =>
      val sourceId = 0.U
      val four = c.io.newEntry.sizeEnumT.FOUR
      c.io.coalReqValid.poke(true.B)
      c.io.newEntry.source.poke(sourceId)
      c.io.newEntry.lanes(0).reqs(0).valid.poke(true.B)
      c.io.newEntry.lanes(0).reqs(0).source.poke(0.U)
      c.io.newEntry.lanes(0).reqs(0).offset.poke(1.U)
      c.io.newEntry.lanes(0).reqs(0).sizeEnum.poke(four)
      c.io.newEntry.lanes(0).reqs(1).valid.poke(false.B)
      c.io.newEntry.lanes(1).reqs(0).valid.poke(true.B)
      c.io.newEntry.lanes(1).reqs(0).source.poke(1.U)
      c.io.newEntry.lanes(1).reqs(0).offset.poke(1.U)
      c.io.newEntry.lanes(1).reqs(0).sizeEnum.poke(four)
      c.io.newEntry.lanes(1).reqs(1).valid.poke(false.B)
      c.io.newEntry.lanes(2).reqs(0).valid.poke(true.B)
      c.io.newEntry.lanes(2).reqs(0).source.poke(2.U)
      c.io.newEntry.lanes(2).reqs(0).offset.poke(1.U)
      c.io.newEntry.lanes(2).reqs(0).sizeEnum.poke(four)
      c.io.newEntry.lanes(2).reqs(1).valid.poke(false.B)
      c.io.newEntry.lanes(3).reqs(0).valid.poke(true.B)
      c.io.newEntry.lanes(3).reqs(0).source.poke(3.U)
      c.io.newEntry.lanes(3).reqs(0).offset.poke(1.U)
      c.io.newEntry.lanes(3).reqs(0).sizeEnum.poke(four)
      c.io.newEntry.lanes(3).reqs(1).valid.poke(false.B)

      c.clock.step()

      c.io.coalReqValid.poke(false.B)

      c.clock.step()

      c.io.coalResp.valid.poke(true.B)
      c.io.coalResp.bits.source.poke(sourceId)
      val lit = (BigInt(0x0123456789abcdefL) << 64) | BigInt(0x5ca1ab1edeadbeefL)
      c.io.coalResp.bits.data.poke(lit.U)

      // table lookup is combinational at the same cycle
      // offset is counting from LSB
      c.io.uncoalResps(0)(0).valid.expect(true.B)
      c.io.uncoalResps(0)(0).bits.data.expect(0x5ca1ab1eL.U)
      c.io.uncoalResps(0)(0).bits.source.expect(0.U)
      c.io.uncoalResps(0)(1).valid.expect(false.B)
      c.io.uncoalResps(1)(0).valid.expect(true.B)
      c.io.uncoalResps(1)(0).bits.data.expect(0x5ca1ab1eL.U)
      c.io.uncoalResps(1)(0).bits.source.expect(1.U)
      c.io.uncoalResps(1)(1).valid.expect(false.B)
      c.io.uncoalResps(2)(0).valid.expect(true.B)
      c.io.uncoalResps(2)(0).bits.data.expect(0x5ca1ab1eL.U)
      c.io.uncoalResps(2)(0).bits.source.expect(2.U)
      c.io.uncoalResps(2)(1).valid.expect(false.B)
      c.io.uncoalResps(3)(0).valid.expect(true.B)
      c.io.uncoalResps(3)(0).bits.data.expect(0x5ca1ab1eL.U)
      c.io.uncoalResps(3)(0).bits.source.expect(3.U)
      c.io.uncoalResps(3)(1).valid.expect(false.B)
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
      testConfig.sizeEnum
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
