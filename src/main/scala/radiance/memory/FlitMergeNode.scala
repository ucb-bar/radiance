package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.{TLAdapterNode, TLMessages}
import freechips.rocketchip.util.UIntIsOneOf
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

// this node merges *separate* TL requests (not 2 beats in 1 logical transfer) of size `from`
// into a single larger request of size `to`. if `alwaysMerge` is true, any non-consecutive
// request that arrives before an ongoing merged request is filled will trigger an assertion.
// any requests that do not have size equal to `from` will be passed through.
class FlitMergeNode(from: Int, to: Int, alwaysMerge: Boolean = true)
                   (implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(
    clientFn  = c => c,
    managerFn = m => {
      require(m.beatBytes == to, "channel must be merged size, use other widgets to ensure")
      m
    }
  )

  lazy val module = new LazyModuleImp(this) {
    require((to > from) && (to % from == 0))
    require(alwaysMerge, "unimplemented")

    (node.in zip node.out).foreach { case ((in, ie), (out, oe)) =>
      require(out.params.dataBits >= to * 8)

      val n = to / from
      val count = Counter(n)
      val mergedReq = RegInit(0.U.asTypeOf(out.a.bits))
      val isFirstReq = count.value === 0.U
      val isLastReq = count.value === (n - 1).U
      val shouldMerge = in.a.bits.opcode.isOneOf(TLMessages.PutFullData, TLMessages.PutPartialData) &&
        (in.a.bits.size === log2Ceil(from).U)

      when (shouldMerge) {
        val byteOffset = (count.value << log2Ceil(from)).asUInt
        val chunkDataMask = (-1.S((from * 8).W) << (byteOffset << 3).asUInt)
          .asTypeOf(UInt(in.params.dataBits.W))
        val chunkMaskMask = (-1.S(from.W) << byteOffset)
          .asTypeOf(UInt((in.params.dataBits / 8).W))
        val filledData = (mergedReq.data & (~chunkDataMask).asUInt) |
          (in.a.bits.data & chunkDataMask)
        val filledMask = (mergedReq.mask & (~chunkMaskMask).asUInt) |
          (in.a.bits.mask & chunkMaskMask)

        when (in.a.fire) {
          count.inc()
          when(isFirstReq) {
            assert((in.a.bits.address & (to - 1).U) === 0.U, "start address not aligned")
            mergedReq := oe.Put(
              fromSource = in.a.bits.source,
              toAddress = in.a.bits.address,
              lgSize = log2Ceil(to).U,
              data = in.a.bits.data,
              mask = in.a.bits.mask,
            )._2
          }.otherwise {
            assert(in.a.bits.address === mergedReq.address + byteOffset)
            mergedReq.data := filledData
            mergedReq.mask := filledMask
          }
        }

        // combinationally set final output data
        val finalOut = WireInit(mergedReq)
        finalOut.data := filledData
        finalOut.mask := filledMask

        out.a.valid := isLastReq
        out.a.bits := finalOut
        in.a.ready := Mux(isLastReq, out.a.ready, true.B)
      }.otherwise {
        out.a <> in.a
      }

      // restore size on D channel if merged on A
      val wasMerged = VecInit.fill(1 << out.a.bits.params.sourceBits)(false.B)
      when (in.a.fire) {
        wasMerged(in.a.bits.source) := shouldMerge
      }
      in.d <> out.d
      in.d.bits.size := Mux(wasMerged(out.d.bits.source), log2Ceil(from).U, out.d.bits.size)
    }
  }
}

object FlitMergeNode {
  def apply(from: Int, to: Int)(implicit p: Parameters): TLAdapterNode = {
    val mergeNode = LazyModule(new FlitMergeNode(from, to))
    mergeNode.node
  }
}