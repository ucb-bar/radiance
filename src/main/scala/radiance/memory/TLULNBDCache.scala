package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes}
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{NonBlockingDCache, PRV, SimpleHellaCacheIF}
import freechips.rocketchip.tile.TileKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import radiance.subsystem.GPUMemory


class TLCToTLULNode(beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val atom = TransferSizes(1, beatBytes)
  // FIX 12: voluntary releases get their own source ids.
  //
  // This node folds the cache's C channel onto the A channel as PutFullData, so a release and an
  // acquire that carry the same source id become two A transactions with one id.  They can overlap:
  // the L0d's flush unit draws its release ids from the MSHR range, and the only thing that kept the
  // two apart was the `flushing` gate on the cpu request port, which drops for two cycles between
  // back-to-back flushes.  Measured (runs/wave_fa, cluster 1 tile 1): `flushing` low at 122635 ns and
  // high again at 122639 ns; at 122643 ns the cache asserts out_a_valid with AcquireBlock source 0
  // and out_c_valid with a Release source 0 in the SAME cycle.  Downstream the TileLink monitor
  // reports "'A' channel re-used a source ID" or "'D' channel contains improper opcode response".
  //
  // Widening the declared space and shifting releases into the top half removes the overlap by
  // construction, so no timing argument is needed.  Every emitted id stays inside a declared
  // IdRange.  Cost: one more source bit on this cache's outward path.
  def releaseIdBase(c: TLMasterPortParameters): Int = c.endSourceId

  val node = TLAdapterNode(
    clientFn  = c => {
      val base = releaseIdBase(c)
      c.v2copy(masters = c.masters.map { m =>
        m.v1copy(
          // probes will be intercepted
          supportsProbe = TransferSizes.none
        )
      } ++ c.masters.map { m =>
        m.v1copy(
          name = m.name + " release",
          supportsProbe = TransferSizes.none,
          sourceId = IdRange(m.sourceId.start + base, m.sourceId.end + base)
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
    (node.in zip node.out).foreach { case ((tlIn, ie), (tlOut, oe)) =>
      // out (ul) has no E bundle, but in (c) does
      val relBase = releaseIdBase(ie.client).U

      tlOut.a.valid := tlIn.a.valid || tlIn.c.valid
      tlIn.a.ready := tlOut.a.ready && (!tlIn.c.valid) // C channel has priority over A
      tlOut.a.bits := tlIn.a.bits
      when (tlIn.c.valid) {
        tlOut.a.bits := oe.Put(tlIn.c.bits.source, tlIn.c.bits.address, tlIn.c.bits.size, tlIn.c.bits.data)._2
        // FIX 12: releases live in the top half of the source space, acquires in the bottom half
        tlOut.a.bits.source := tlIn.c.bits.source +& relBase
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
      // FIX 12: the id says which channel the response belongs to, so it no longer has to be guessed
      // from the opcode, and the release's own id is handed back to the cache unshifted
      val dIsRelease = tlOut.d.bits.source >= relBase
      tlIn.d.bits.source := Mux(dIsRelease, tlOut.d.bits.source - relBase, tlOut.d.bits.source)
      tlIn.d.bits.opcode := Mux(dIsRelease,
        TLMessages.ReleaseAck, // response for C channel ReleaseData
        TLMessages.GrantData   // response for A channel AcquireBlock
      )
      assert(!tlOut.d.valid || (dIsRelease === (tlOut.d.bits.opcode === TLMessages.AccessAck)),
        "release/acquire response does not match the half of the source space it came back on")
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

  DisableMonitors { implicit p =>
    outNode :=* c2ulNode :=* tlcOutNode
  }

  override lazy val module = new TLULNBDCacheModule(this)
}

class TLULNBDCacheModule(outer: TLULNBDCache) extends LazyModuleImp(outer)
  with MemoryOpConstants {

  require(outer.outNode.in.length == 1, s"tlcOutNode has ${outer.outNode.in.length} inputs")

  // val (tlIn, _) = outer.outNode.in.head
  // val (tlOut, oe) = outer.outNode.out.head
}

