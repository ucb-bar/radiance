package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{NonBlockingDCache, PRV, SimpleHellaCacheIF}
import freechips.rocketchip.tile.TileKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import radiance.subsystem.GPUMemory


class TLCToTLULNode(beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val atom = TransferSizes(1, beatBytes)
  val node = TLAdapterNode(
    clientFn  = c => {
      c.v2copy(masters = c.masters.map { m =>
        m.v1copy(
          // probes will be intercepted
          supportsProbe = TransferSizes.none
        )
      })
    },
    managerFn = m => {
      m.v2copy(slaves = m.slaves.map { s =>
        s.v1copy(
          // this allows read permissions (branch)
          supportsAcquireB = s.supportsAcquireB mincover atom,
          // this allows write permissions (trunk)
          supportsAcquireT = s.supportsAcquireT mincover atom,
        )
      })
    }
  )
  lazy val module = new LazyModuleImp(this) {
    (node.in.map(_._1) zip node.out).foreach { case (tlIn, (tlOut, oe)) =>
      // out (ul) has no E bundle, but in (c) does

      tlOut.a.valid := tlIn.a.valid || tlIn.c.valid
      tlIn.a.ready := tlOut.a.ready && (!tlIn.c.valid) // C channel has priority over A
      tlOut.a.bits := tlIn.a.bits
      when (tlIn.c.valid) {
        tlOut.a.bits := oe.Put(tlIn.c.bits.source, tlIn.c.bits.address, tlIn.c.bits.size, tlIn.c.bits.data)._2
        tlOut.a.bits.source := tlIn.c.bits.source
        // tlOut.a.bits.data := tlIn.c.bits.data
        // tlOut.a.bits.size := tlIn.c.bits.size
      }
      // C channel ReleaseData translates to PutFull, A channel AcquireBlock translates to Get
      tlOut.a.bits.opcode := Mux(tlIn.c.valid, TLMessages.PutFullData, TLMessages.Get)
      // squash A channel param to 0 (normally represents permission change, we don't want this)
      tlOut.a.bits.param := 0.U

      assert(!tlIn.a.valid || (tlIn.a.bits.opcode === TLMessages.AcquireBlock))
      assert(!tlIn.c.valid || (tlIn.c.bits.opcode === TLMessages.ReleaseData))

      assert(!tlOut.b.valid, "no probes allowed")

      tlOut.c.valid := false.B
      tlOut.c.bits := DontCare
      tlIn.c.ready := tlOut.a.ready // priority over A

      tlIn.d.valid := tlOut.d.valid
      tlIn.d.bits := tlOut.d.bits
      tlIn.d.bits.opcode := Mux(tlOut.d.bits.opcode === TLMessages.AccessAckData,
        TLMessages.GrantData, // response for A channel AcquireBlock
        TLMessages.ReleaseAck // response for C channel ReleaseData
      )
      tlIn.d.bits.sink := Counter(
        tlIn.d.fire && tlIn.d.bits.opcode === TLMessages.GrantData,
        1 << tlIn.params.sinkBits)._1
      tlOut.d.ready := tlIn.d.ready

      tlOut.e.valid := false.B
      tlOut.e.bits := DontCare
      tlIn.e.ready := true.B // silently sink GrantAck's

      dontTouch(tlIn.a)
      dontTouch(tlIn.b)
      dontTouch(tlIn.c)
      dontTouch(tlIn.d)
      dontTouch(tlIn.e)
      dontTouch(tlOut.a)
      dontTouch(tlOut.b)
      dontTouch(tlOut.c)
      dontTouch(tlOut.d)
      dontTouch(tlOut.e)
    }
  }
}

class TLULNBDCache(params: TLNBDCacheParams)
                  (implicit p: Parameters) extends LazyModule {

  val tlnbdCache = LazyModule(new TLNBDCache(params))

  val beatBytes = params.cache.blockBytes
  val inNode = tlnbdCache.inNode
  val tlcOutNode = tlnbdCache.outNode
  val flushRegNode = tlnbdCache.flushRegNode
  val flushNode = tlnbdCache.flushNode
  val c2ulNode = LazyModule(new TLCToTLULNode(beatBytes)).node
  val outNode = TLIdentityNode()

  outNode :=* c2ulNode :=* tlcOutNode

  override lazy val module = new TLULNBDCacheModule(this)
}

class TLULNBDCacheModule(outer: TLULNBDCache) extends LazyModuleImp(outer)
  with MemoryOpConstants {

  require(outer.outNode.in.length == 1, s"tlcOutNode has ${outer.outNode.in.length} inputs")

  // val (tlIn, _) = outer.outNode.in.head
  // val (tlOut, oe) = outer.outNode.out.head
}

