package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.TLAdapterNode
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

class ResponseFIFOFixer(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(clientFn = c => c, managerFn = m => m)

  lazy val module = new LazyModuleImp(this) {

    (node.out zip node.in).foreach { case ((ob, _), (ib, _)) =>
      val sourceBits = ib.params.sourceBits

      val buffer = RegInit(VecInit.fill(1 << sourceBits)(0.U.asTypeOf(Valid(ob.d.bits.cloneType))))
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
      val bufferedValid = buffer(oldestSource).valid

      ib.d.valid := inOrderValid || bufferedValid
      ib.d.bits := Mux(inOrderValid, ob.d.bits, buffer(oldestSource).bits)
      ob.d.ready := Mux(inOrderValid, ib.d.ready, !buffer(ob.d.bits.source).valid)

      when (ib.d.fire) {
        when (bufferedValid) {
          // mark buffer entry as invalid
          bufferedValid := false.B
        }
      }

      when (ob.d.fire) {
        when (!responseInOrder) {
          // store ooo response in buffer
          buffer(ob.d.bits.source).bits := ob.d.bits
          buffer(ob.d.bits.source).valid := true.B
        }
      }

      assert(!(inOrderValid && bufferedValid),
        "conflicting sources of d bits")
      assert(!inOrderValid || (ib.d.fire === ob.d.fire),
        "in order entry dropped")
      assert(!ob.d.fire || inOrderValid || !buffer(ob.d.bits.source).valid,
        "writing ooo entry that's already valid")
    }
  }
}

object ResponseFIFOFixer {
  def apply()(implicit p: Parameters) = {
    LazyModule(new ResponseFIFOFixer).node
  }
}