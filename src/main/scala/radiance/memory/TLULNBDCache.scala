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


class HackAcquireNode(beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(
    clientFn  = c => c,
    managerFn = m => {
      val atom = TransferSizes(1, beatBytes)
      m.v2copy(slaves = m.slaves.map( s => {
        s.v1copy(
          // this allows read permissions (branch)
          supportsAcquireB = s.supportsAcquireB mincover atom,
          // this allows write permissions (trunk)
          supportsAcquireT = s.supportsAcquireT mincover atom,
        )
      }))
    }
  )
  lazy val module = new LazyModuleImp(this) {
    (node.in.map(_._1) zip node.out.map(_._1)).foreach { case (i, o) => o <> i }
  }
}

class TLULNBDCache(staticIdForMetadataUseOnly: Int,
                   overrideDChannelSize: Option[Int] = None)
                  (implicit p: Parameters) extends LazyModule {

  val tlnbdCache = LazyModule(new TLNBDCache(staticIdForMetadataUseOnly, overrideDChannelSize))


  val beatBytes = p(TileKey).dcache.get.blockBytes
  val inNode = tlnbdCache.inNode
  val tlcOutNode = tlnbdCache.outNode
  val acquireNode = LazyModule(new HackAcquireNode(beatBytes)).node
  val outNode = TLIdentityNode()

  outNode :=* /* acquireNode :=* */ tlcOutNode

  override lazy val module = new TLULNBDCacheModule(this)
}

class TLULNBDCacheModule(outer: TLULNBDCache) extends LazyModuleImp(outer)
  with MemoryOpConstants {

  require(outer.outNode.in.length == 1, s"tlcOutNode has ${outer.outNode.in.length} inputs")

  val (tlIn, _) = outer.outNode.in.head
  val (tlOut, oe) = outer.outNode.out.head

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

