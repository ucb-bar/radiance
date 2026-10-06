package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.{TLAdapterNode, TLBundle, TLEdgeIn}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

/** Returns D responses in the order of their A requests.
  *
  * `maxInFlight = None` keeps one buffered response per source id, so the buffer has
  * 2^(sourceBits - throwAwayPrefix) entries whatever the real concurrency is.
  *
  * `maxInFlight = Some(n)` keeps n entries indexed by arrival order: every A takes the next slot of an
  * n-entry ring, a small table maps its source to that slot, and A is refused while all n slots are
  * occupied.  The refusal makes the fixer correct for any n >= 1 without a bound on how many requests
  * the client keeps in flight; n only sets how much reordering it can absorb before it slows A down.
  * Holding A cannot deadlock as long as the manager's responses do not wait on further requests from
  * this client and the client always accepts D, which holds for the Radiance caches behind it.
  */
class ResponseFIFOFixer(throwAwayPrefix: Int = 0, maxInFlight: Option[Int] = None)
                       (implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(clientFn = c => c, managerFn = m => m)

  lazy val module = new LazyModuleImp(this) {

    (node.out zip node.in).foreach { case ((ob, _), (ib, edgeIn)) =>
      maxInFlight match {
        case None    => sourceIndexed(ob, ib)
        case Some(n) => arrivalIndexed(ob, ib, edgeIn, n)
      }
    }

    def sourceIndexed(ob: TLBundle, ib: TLBundle): Unit = {
      val sourceBits = ib.params.sourceBits
      val entryBits = sourceBits - throwAwayPrefix
      val buffer = RegInit(VecInit.fill(1 << entryBits)(0.U.asTypeOf(Valid(ob.d.bits.cloneType))))
      val ids = Module(new Queue(UInt(sourceBits.W), 1 << sourceBits, false, true))

      // track all outgoing A request sources
      ids.io.enq.valid := ib.a.fire
      ids.io.enq.bits := ib.a.bits.source
      assert(!ib.a.fire || ib.a.ready)

      // dequeue sources in order
      ids.io.deq.ready := ib.d.fire
      assert(!ib.d.fire || ib.d.valid)

      // A requests are pass through
      ob.a <> ib.a

      // we store D
      val oldestSource = WireInit(ids.io.deq.bits)
      val responseInOrder = oldestSource === ob.d.bits.source
      val inOrderValid = responseInOrder && ob.d.valid
      val bufferedValid = buffer(oldestSource(entryBits - 1, 0)).valid
      val currDinBuf = buffer(ob.d.bits.source(entryBits - 1, 0))

      ib.d.valid := inOrderValid || bufferedValid
      ib.d.bits := Mux(inOrderValid, ob.d.bits, buffer(oldestSource(entryBits -  1, 0)).bits)
      ob.d.ready := Mux(inOrderValid, ib.d.ready, !currDinBuf.valid)

      when (ib.d.fire) {
        when (bufferedValid) {
          // mark buffer entry as invalid
          bufferedValid := false.B
        }
      }

      when (ob.d.fire) {
        when (!responseInOrder) {
          // store ooo response in buffer
          currDinBuf.bits := ob.d.bits
          currDinBuf.valid := true.B
        }
      }

      assert(!(inOrderValid && bufferedValid),
        "conflicting sources of d bits")
      assert(!inOrderValid || (ib.d.fire === ob.d.fire),
        "in order entry dropped")
      assert(!ob.d.fire || inOrderValid || !currDinBuf.valid,
        "writing ooo entry that's already valid")
    }

    def arrivalIndexed(ob: TLBundle, ib: TLBundle, edgeIn: TLEdgeIn, n: Int): Unit = {
      require(n >= 1, s"ResponseFIFOFixer: maxInFlight must be at least 1, got $n")
      // One slot per transaction and one D beat per slot: both directions must be single-beat.  The
      // largest request the client emits bounds the largest response.
      val maxEmitted = edgeIn.client.masters.map { m =>
        val e = m.emits
        Seq(e.acquireT, e.acquireB, e.arithmetic, e.logical, e.get, e.putFull, e.putPartial, e.hint)
          .map(_.max).max
      }.max
      require(maxEmitted <= edgeIn.manager.beatBytes,
        s"ResponseFIFOFixer: maxInFlight needs single-beat transfers, but the client emits up to " +
        s"$maxEmitted B on a ${edgeIn.manager.beatBytes} B channel")

      val entryBits = ib.params.sourceBits - throwAwayPrefix
      val slotBits = log2Ceil(n) max 1
      def nextSlot(s: UInt): UInt = Mux(s === (n - 1).U, 0.U, s + 1.U)

      val slotOf = Reg(Vec(1 << entryBits, UInt(slotBits.W)))
      val buffer = Reg(Vec(n, ob.d.bits.cloneType))
      val buffered = RegInit(VecInit.fill(n)(false.B))
      val enqSlot = RegInit(0.U(slotBits.W))
      val deqSlot = RegInit(0.U(slotBits.W))
      val count = RegInit(0.U(log2Ceil(n + 1).W))

      // A passes through, refused while every slot is occupied
      ob.a <> ib.a
      val full = count === n.U
      ob.a.valid := ib.a.valid && !full
      ib.a.ready := ob.a.ready && !full
      val aIdx = ib.a.bits.source(entryBits - 1, 0)
      when (ib.a.fire) {
        slotOf(aIdx) := enqSlot
        enqSlot := nextSlot(enqSlot)
      }

      // D: the oldest slot passes straight through; any other response waits in its slot
      val dIdx = ob.d.bits.source(entryBits - 1, 0)
      // a response in the same cycle as its own request reads the slot being assigned
      val dSlot = Mux(ib.a.fire && aIdx === dIdx, enqSlot, slotOf(dIdx))
      val inOrderValid = ob.d.valid && dSlot === deqSlot
      val headBuffered = buffered(deqSlot)

      ib.d.valid := inOrderValid || headBuffered
      ib.d.bits := Mux(inOrderValid, ob.d.bits, buffer(deqSlot))
      ob.d.ready := Mux(inOrderValid, ib.d.ready, !buffered(dSlot))

      when (ob.d.fire && !inOrderValid) {
        buffer(dSlot) := ob.d.bits
        buffered(dSlot) := true.B
      }
      when (ib.d.fire) {
        when (headBuffered) { buffered(deqSlot) := false.B }
        deqSlot := nextSlot(deqSlot)
      }
      count := count + ib.a.fire.asUInt - ib.d.fire.asUInt

      assert(!(inOrderValid && headBuffered), "ResponseFIFOFixer: conflicting sources of d bits")
      assert(!inOrderValid || (ib.d.fire === ob.d.fire), "ResponseFIFOFixer: in order entry dropped")
      assert(!ob.d.fire || inOrderValid || !buffered(dSlot),
        "ResponseFIFOFixer: writing ooo entry that's already valid")
      assert(!ob.d.valid || count =/= 0.U || ib.a.fire, "ResponseFIFOFixer: response with no request in flight")
      assert(!ib.a.valid || edgeIn.numBeats1(ib.a.bits) === 0.U, "ResponseFIFOFixer: multi-beat request")
      assert(!ob.d.valid || edgeIn.numBeats1(ob.d.bits) === 0.U, "ResponseFIFOFixer: multi-beat response")
    }
  }
}

object ResponseFIFOFixer {
  def apply()(implicit p: Parameters) = {
    LazyModule(new ResponseFIFOFixer).node
  }

  def apply(throwAwayPrefix: Int)(implicit p: Parameters) = {
    LazyModule(new ResponseFIFOFixer(throwAwayPrefix)).node
  }

  /** At most `maxInFlight` requests outstanding; see the class comment. */
  def bounded(maxInFlight: Int)(implicit p: Parameters) = {
    LazyModule(new ResponseFIFOFixer(maxInFlight = Some(maxInFlight))).node
  }
}
