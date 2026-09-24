package chipyard

import org.chipsalliance.cde.config.{Config, Parameters}
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.ElaborationArtefacts
import sifive.blocks.inclusivecache._
import radiance.subsystem.{GPUMemory, L2CacheSpec, SplitL2, SplitL2Key, SplitL2Params, WithSplitL2Topology}

/** Builds one InclusiveCache for a split-L2 spec. The same chain rocket's WithInclusiveCache
  * builds (filter, buffers, cache, cork), with the geometry and control block taken from the
  * spec; the remaining micro-parameters come from InclusiveCacheKey. It lives in the chipyard
  * package because InclusiveCache is not visible to the radiance sbt project. */
object RadianceInclusiveCacheFactory {
  val make: SplitL2.CacheFactory = { (context, spec) =>
    implicit val p: Parameters = context.p
    val sbus = context.tlBusWrapperLocationMap(SBUS)
    val cbus = context.tlBusWrapperLocationMap.lift(CBUS).getOrElse(sbus)
    val key = p(InclusiveCacheKey)
    val sets = spec.kBPerSlice * 1024 / (sbus.blockBytes * spec.ways)
    require(sets > 0 && (sets & (sets - 1)) == 0,
      s"L2 cache ${spec.name}: ${spec.kBPerSlice} KiB per slice with ${spec.ways} ways gives $sets sets, not a power of two")

    val l2 = LazyModule(new InclusiveCache(
      CacheParameters(
        level = 2,
        ways = spec.ways,
        sets = sets,
        blockBytes = sbus.blockBytes,
        beatBytes = sbus.beatBytes,
        hintsSkipProbe = key.hintsSkipProbe),
      InclusiveCacheMicroParameters(
        writeBytes = key.writeBytes,
        portFactor = key.portFactor,
        memCycles = key.memCycles,
        innerBuf = key.bufInnerInterior,
        outerBuf = key.bufOuterInterior),
      spec.ctrlAddr.map(a => InclusiveCacheControlParameters(
        address = a, beatBytes = cbus.beatBytes, bankedControl = false))))
    l2.suggestName(s"l2_${spec.name}")

    // same MMIO skip as rocket's coherence manager: the rocket dcache's uncached client
    def skipMMIO(x: TLClientParameters) = {
      val dcacheMMIO = x.requestFifo && x.sourceId.start % 2 == 1 && x.nodePath.last.name == "dcache.node"
      if (dcacheMMIO) None else Some(x)
    }
    val filter = LazyModule(new TLFilter(cfilter = skipMMIO))
    val innerBuffer = key.bufInnerExterior()
    val outerBuffer = key.bufOuterExterior()
    val cork = LazyModule(new TLCacheCork)

    innerBuffer.node :*= filter.node
    l2.node :*= innerBuffer.node
    outerBuffer.node :*= l2.node
    cork.node :*= outerBuffer.node

    l2.ctrls.foreach {
      _.ctrlnode := cbus.coupleTo(s"l2_${spec.name}_ctrl") { TLBuffer(1) := TLFragmenter(cbus, Some("LLCCtrl")) := _ }
    }
    ElaborationArtefacts.add(s"l2_${spec.name}.json", l2.module.json)
    (filter.node, cork.node)
  }
}

/** Two independent L2 caches on the unchanged memory map: a host cache with one slice over the
  * host part of ExtMem, and a GPU cache with `gpuSlices` contiguous slices over GPU memory. Each
  * cache has its own capacity. Combine with WithRadianceRegionMem for one DRAM channel per slice.
  * The host cache keeps rocket's L2 control address; the GPU cache's control block is 4 KiB above. */
class WithRadianceSplitL2(hostKB: Int, gpuSlices: Int, gpuKBPerSlice: Int, ways: Int = 8) extends Config(
  new WithSplitL2Topology(RadianceInclusiveCacheFactory.make) ++
  new Config((site, here, up) => {
    case SplitL2Key => {
      val ext = site(ExtMem).getOrElse(throw new Exception("WithRadianceSplitL2 needs ExtMem"))
      val gpu = site(GPUMemory).getOrElse(throw new Exception("WithRadianceSplitL2 needs GPUMemory"))
      require(gpuSlices > 0 && (gpuSlices & (gpuSlices - 1)) == 0, s"gpuSlices must be a power of two, got $gpuSlices")
      require(gpu.address + gpu.size == ext.master.base + ext.master.size,
        "WithRadianceSplitL2 expects GPU memory at the top of ExtMem (WithExtGPUMem)")
      val stripe = gpu.size / gpuSlices
      val ctrl = BigInt(InclusiveCacheParameters.L2ControlAddress)
      Some(SplitL2Params(Seq(
        L2CacheSpec("host", Seq(AddressSet(ext.master.base, gpu.address - ext.master.base - 1)),
          kBPerSlice = hostKB, ways = ways, ctrlAddr = Some(ctrl)),
        L2CacheSpec("gpu", Seq.tabulate(gpuSlices)(i => AddressSet(gpu.address + i * stripe, stripe - 1)),
          kBPerSlice = gpuKBPerSlice, ways = ways, ctrlAddr = Some(ctrl + 0x1000)))))
    }
  })
)

/** Widen the sink-id field of every serial-TL port. Each L2 bank behind the split wrapper's inner
  * crossbar gets its own power-of-two sink range, so 5 banks need 9 sink bits where the standard
  * serial-TL bundle has 8. */
class WithSerialTLSinkBits(bits: Int) extends Config((site, here, up) => {
  case testchipip.serdes.SerialTLKey => up(testchipip.serdes.SerialTLKey).map(s =>
    s.copy(bundleParams = s.bundleParams.copy(sinkBits = bits)))
})
