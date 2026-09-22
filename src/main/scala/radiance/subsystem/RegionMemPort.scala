package radiance.subsystem

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.amba.axi4.{AXI4IdIndexer, AXI4SlaveNode, AXI4SlaveParameters, AXI4SlavePortParameters, AXI4UserYanker}
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.resources.MemoryDevice
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink.{TLToAXI4, TLWidthWidget, TLXbar}

/** One AXI4 memory port per contiguous address region, instead of rocket-chip's block-granular
  * interleave across channels. Paired with WithL2Slices at the same granularity this gives a 1:1
  * L2-slice-to-DRAM-channel mapping. */
case class RadianceMemRegionParams(regions: Seq[AddressSet], beatBytes: Int, idBits: Int)

case object RadianceMemRegionsKey extends Field[Option[RadianceMemRegionParams]](None)

object RadianceMemRegions {
  /** The regions a BankBinder with this mask carves out of [base, base+size), in bank order, so
    * region i is exactly the range L2 slice i owns. */
  def fromMask(base: BigInt, size: BigInt, mask: BigInt): Seq[AddressSet] = {
    val whole = AddressSet.misaligned(base, size)
    AddressSet.enumerateMask(mask).map { id =>
      val parts = whole.flatMap(_.intersect(AddressSet(id, ~mask)))
      require(parts.size == 1,
        s"region for bank id ${id.toString(16)} is not one contiguous AddressSet: $parts. " +
        "Region memory needs a mask whose classes are each contiguous over ExtMem.")
      parts.head
    }
  }
}

trait CanHaveRadianceRegionMemPort { this: BaseSubsystem =>
  private val regionParams = p(RadianceMemRegionsKey)
  private val mbus = tlBusWrapperLocationMap.get(MBUS).getOrElse(viewpointBus)
  private val device = new MemoryDevice

  val radianceMemAXI4Node = AXI4SlaveNode(regionParams.toList.flatMap { rp =>
    rp.regions.map { region =>
      AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = Seq(region),
          resources     = device.reg,
          regionType    = RegionType.UNCACHED,
          executable    = true,
          supportsWrite = TransferSizes(1, mbus.blockBytes),
          supportsRead  = TransferSizes(1, mbus.blockBytes),
          interleavedId = Some(0))),
        beatBytes = rp.beatBytes)
    }
  })

  regionParams.foreach { rp =>
    rp.regions.indices.foreach { i =>
      val xbar = mbus { TLXbar() }
      mbus.coupleTo(s"radiance_region_mem_$i") {
        // Monitors are disabled for the same reason rocket does it on this path: the enclosing
        // class provides no implicit clock for them.
        (DisableMonitors { implicit p => radianceMemAXI4Node := AXI4UserYanker() }
          := AXI4IdIndexer(rp.idBits)
          := TLToAXI4()
          := TLWidthWidget(mbus.beatBytes)
          := xbar
          := _)
      }
    }
  }

  val radiance_mem_axi4 = InModuleBody { radianceMemAXI4Node.makeIOs() }
}
