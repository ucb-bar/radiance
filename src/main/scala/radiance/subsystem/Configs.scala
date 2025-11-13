// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.subsystem

import chisel3._
import chisel3.util._
import freechips.rocketchip.prci._
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.rocket.DCacheParams
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink.{TLBusWrapperConnection, TLBusWrapperTopology}
import gemmini.Arithmetic.FloatArithmetic._
import gemmini._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.nodes._
import testchipip.soc.SubsystemInjectorKey
import radiance.cluster._
import radiance.memory._
import radiance.muon._
import radiance.virgo.{NumVortexCores, VirgoClusterParams, VortexCoreParams, VortexL1Key}
import radiance.muon.LoadStoreUnitParams

sealed trait RadianceSmemSerialization
case object FullySerialized extends RadianceSmemSerialization
case object CoreSerialized extends RadianceSmemSerialization
case object NotSerialized extends RadianceSmemSerialization

sealed trait MemType
case object TwoPort extends MemType
case object TwoReadOneWrite extends MemType

case class RadianceSharedMemKey(address: BigInt,
                                size: Int,
                                numBanks: Int,
                                numWords: Int,
                                wordSize: Int = 4,
                                prealignBufDepth: Int = 2,
                                memType: MemType = TwoPort,
                                strideByWord: Boolean = true,
                                filterAligned: Boolean = true,
                                disableMonitors: Boolean = true,
                                serialization: RadianceSmemSerialization = FullySerialized)
case object RadianceSharedMemKey extends Field[Option[RadianceSharedMemKey]](None)

case class RadianceFrameBufferKey(baseAddress: BigInt,
                                  width: Int,
                                  size: Int,
                                  validAddress: BigInt,
                                  fbName: String = "fb")
case object RadianceFrameBufferKey extends Field[Seq[RadianceFrameBufferKey]](Seq())

case class SIMTCoreParams(
  numWarps: Int = 4,        // # of warp slots in the core
  numLanes: Int = 4,        // # of SIMT lanes per warp
  numLsuLanes: Int = 4,     // # of LSU lanes in the core
  numSMEMInFlights: Int = 8 // # of in-flight SMEM requests handled in the LSU
)
case object SIMTCoreKey extends Field[Option[SIMTCoreParams]](None)

class WithMuonCores(
  n: Int,
  location: HierarchicalLocation,
  crossing: RocketCrossingParams,
  headless: Boolean,
  l0i: Option[DCacheParams],
  l0d: Option[DCacheParams],
) extends Config((site, here, up) => {
  // for use in tile-less standalone instantiation
  case MuonKey => {
    MuonCoreParams(
      numWarps = up(SIMTCoreKey).get.numWarps,
      numLanes = up(SIMTCoreKey).get.numLanes,
      numCores = n,
      numClusters = 2, // TODO: magic number
      logSMEMInFlights = log2Ceil(up(SIMTCoreKey).get.numSMEMInFlights),

      lsu = LoadStoreUnitParams(
        numLsuLanes = up(SIMTCoreKey).get.numLsuLanes
      )
    )
  }
  case TilesLocated(`location`) => {
    if (headless) {
      Seq()
    } else {
      val prev = up(TilesLocated(`location`))
      val idOffset = up(NumTiles)
      val coreIdOffset = up(NumMuonCores)
      require(up(SIMTCoreKey).isDefined, "WithMuonCores requires WithSIMTConfig")

      val clusterParams = (location match {
        case InCluster(id) => Some(site(ClustersLocated(InSubsystem))(id))
        case _ => None
      }).get.clusterParams.asInstanceOf[RadianceClusterParams]

      val muon = MuonTileParams(
        core = here(MuonKey),
        icache = None,
        icacheUsingD = l0i,
        dcache = l0d,
        l1CacheLineBytes = clusterParams.l1Config.blockBytes
      )
      List.tabulate(n)(i => MuonTileAttachParams(
        muon.copy(
          tileId = i + idOffset,
          coreId = i + coreIdOffset,
          clusterId = clusterParams.clusterId,
        ),
        crossing
      )) ++ prev
    }
  }
  case NumTiles => up(NumTiles) + n
  case NumMuonCores => up(NumMuonCores) + n
}) {
  // constructor override that omits `crossing`
  def this(n: Int, location: HierarchicalLocation = InSubsystem, headless: Boolean = false,
          l0i: Option[DCacheParams] = None, l0d: Option[DCacheParams] = None)
  = this(n, location, RocketCrossingParams(
    master = HierarchicalElementMasterPortParams.locationDefault(location),
    slave = HierarchicalElementSlavePortParams.locationDefault(location),
    mmioBaseAddressPrefixWhere = location match {
      case InSubsystem => CBUS
      case InCluster(clusterId) => CCBUS(clusterId)
    },
  ), headless, l0i, l0d)
}

class WithCyclotronCores(
  n: Int
) extends Config((site, _, up) => {
  case TilesLocated(InSubsystem) => {
    val prev = up(TilesLocated(InSubsystem))
    val idOffset = up(NumTiles)
    val cyclotron = CyclotronTileParams(
      core = MuonCoreParams())
    List.tabulate(n)(i => CyclotronTileAttachParams(
      cyclotron.copy(tileId = i + idOffset),
      RocketCrossingParams()
    )) ++ prev
  }
  case NumTiles => up(NumTiles) + 1
  case NumVortexCores => up(NumVortexCores) + 1
})

class WithFuzzerCores(
  n: Int,
  useVxCache: Boolean
) extends Config((site, _, up) => {
  case TilesLocated(InSubsystem) => {
    val prev = up(TilesLocated(InSubsystem))
    val idOffset = up(NumTiles)
    val fuzzer = FuzzerTileParams(
      core = VortexCoreParams(),
      useVxCache = useVxCache)
    List.tabulate(n)(i => FuzzerTileAttachParams(
      fuzzer.copy(tileId = i + idOffset),
      RocketCrossingParams()
    )) ++ prev
  }
  case NumTiles => up(NumTiles) + 1
  case NumVortexCores => up(NumVortexCores) + 1
})

object RadianceGemminiDataType extends Enumeration {
  type Type = Value
  val FP32, FP16, BF16, Int8 = Value
}

class WithRadianceGemmini(location: HierarchicalLocation, crossing: RocketCrossingParams,
                          dim: Int, accSizeInKB: Int, tileSize: Either[(Int, Int, Int), Int],
                          dataType: RadianceGemminiDataType.Type, dmaBytes: Int,
                          hasAccSlave: Boolean) extends Config((site, _, up) => {
  case TilesLocated(`location`) => {
    val prev = up(TilesLocated(`location`))
    val idOffset = up(NumTiles)
    if (idOffset == 0) {
      // FIXME: this doesn't work for multiple clusters when idOffset may not be 0
      println("******WARNING****** gemmini tile id is 0! radiance tiles in the same cluster needs to be before gemmini")
    }
    val numPrevGemminis = prev.map(_.tileParams).map {
      case _: GemminiTileParams => 1
      case _ => 0
    }.sum

    val clusterParams = (location match {
      case InCluster(id) => Some(site(ClustersLocated(InSubsystem))(id))
      case _ => None
    }).get.clusterParams

    val smKey = (clusterParams match {
      case params: RadianceClusterParams => Some(params.smemConfig)
      case _: VirgoClusterParams => site(RadianceSharedMemKey)
      case _ =>
        assert(false, "this config requires either a radiance cluster or a virgo cluster")
        None
    }).get

    val skipRecoding = false
    val tileParams = GemminiTileParams(
      gemminiConfig = {
        implicit val arithmetic: Arithmetic[Float] =
          Arithmetic.FloatArithmetic.asInstanceOf[Arithmetic[Float]]
        dataType match {
        case RadianceGemminiDataType.FP32 => GemminiFPConfigs.FP32DefaultConfig
        case RadianceGemminiDataType.FP16 => GemminiFPConfigs.FP16DefaultConfig.copy(
          acc_scale_args = Some(ScaleArguments(
            (t: Float, u: Float) => {t},
            1, Float(8, 24), -1, identity = "1.0", c_str = "((x))"
          )),
          mvin_scale_args = Some(ScaleArguments(
            (t: Float, u: Float) => t * u,
            1, Float(5, 11), -1, identity = "0x3c00", c_str="((x) * (scale))"
          )),
          mvin_scale_acc_args = None,
          has_training_convs = false,

          // from sirius
          spatialArrayInputType = Float(5, 11, isRecoded = skipRecoding),
          spatialArrayWeightType = Float(5, 11, isRecoded = skipRecoding),
          spatialArrayOutputType = Float(8, 24, isRecoded = skipRecoding),
          accType = Float(8, 24),
          // hardcode_d_to_garbage_addr = true,
          acc_read_full_width = false, // set to true to output fp32

          // acc_singleported = true,
          // clock_gate = true,
          num_counter = 0
        )
        case RadianceGemminiDataType.BF16 => GemminiFPConfigs.BF16DefaultConfig
        // TODO: Int8
      }}.copy(
        dataflow = Dataflow.WS,
        ex_read_from_acc = false,
        ex_write_to_spad = false,
        has_training_convs = false,
        has_max_pool = false,
        use_tl_ext_mem = true,
        sp_singleported = false,
        spad_read_delay = 4,
        use_shared_ext_mem = true,
        acc_sub_banks = 1,
        has_normalizations = false,
        meshRows = dim,
        meshColumns = dim,
        tile_latency = 0,
        mesh_output_delay = 1,
        acc_latency = 3,
        dma_maxbytes = site(CacheBlockBytes),
        dma_buswidth = dmaBytes,
        tl_ext_mem_base = smKey.address,
        sp_banks = smKey.numBanks,
        sp_capacity = CapacityInKilobytes(smKey.size >> 10),
        acc_capacity = CapacityInKilobytes(accSizeInKB),
      ),
      tileId = idOffset,
      tileSize = tileSize,
      slaveAddress = smKey.address + smKey.size + 0x3000 + 0x100 * numPrevGemminis,
      hasAccSlave = hasAccSlave,
    )
    Seq(GemminiTileAttachParams(
      tileParams,
      crossing
    )) ++ prev
  }
  case NumTiles => up(NumTiles) + 1
}) {
  def this(location: HierarchicalLocation, dim: Int, accSizeInKB: Int, tileSize: Either[(Int, Int, Int), Int],
           dataType: RadianceGemminiDataType.Type = RadianceGemminiDataType.FP32,
           dmaBytes: Int = 256, hasAccSlave: Boolean = true) =
    this(location, RocketCrossingParams(
      master = HierarchicalElementMasterPortParams.locationDefault(location),
      slave = HierarchicalElementSlavePortParams.locationDefault(location),
      mmioBaseAddressPrefixWhere = location match {
        case InSubsystem => CBUS
        case InCluster(clusterId) => CCBUS(clusterId)
      }
    ), dim, accSizeInKB, tileSize, dataType, dmaBytes, hasAccSlave)

  def this(location: HierarchicalLocation, dim: Int, accSizeInKB: Int, tileSize: Int) =
    this(location, dim, accSizeInKB, Right(tileSize))

  def this(location: HierarchicalLocation, dim: Int, accSizeInKB: Int, tileSize: (Int, Int, Int),
           dataType: RadianceGemminiDataType.Type) =
    this(location, dim, accSizeInKB, Left(tileSize), dataType)
}

class WithRadianceMxGemmini(location: HierarchicalLocation, crossing: RocketCrossingParams,
                            dim: Int, accSizeInKB: Int, tileSize: (Int, Int, Int))
  extends Config((site, _, up) => {

  case TilesLocated(`location`) => {
    val prev = up(TilesLocated(`location`))
    val idOffset = up(NumTiles)

    val clusterParams = (location match {
      case InCluster(id) => Some(site(ClustersLocated(InSubsystem))(id))
      case _ => None
    }).get.clusterParams

    val smKey = (clusterParams match {
      case params: RadianceClusterParams => Some(params.smemConfig)
      case _ =>
        assert(false, "this config requires a radiance cluster")
        None
    }).get

    val tileParams = GemminiTileParams(
      gemminiConfig = {
        implicit val arithmetic: Arithmetic[Float] =
          Arithmetic.FloatArithmetic.asInstanceOf[Arithmetic[Float]]
        GemminiFPConfigs.FP16DefaultConfig.copy(
          acc_scale_args = Some(ScaleArguments(
            (t: Float, u: Float) => {t},
            1, Float(8, 24), -1, identity = "1.0", c_str = "((x))"
          )),
          mvin_scale_args = Some(ScaleArguments(
            (t: Float, u: Float) => t * u,
            1, Float(5, 11), -1, identity = "0x3c00", c_str="((x) * (scale))"
          )),
          mvin_scale_acc_args = None,
          has_training_convs = false,
          spatialArrayInputType = Float(5, 11, isRecoded = false),
          spatialArrayWeightType = Float(5, 11, isRecoded = false),
          spatialArrayOutputType = Float(8, 24, isRecoded = false),
          accType = Float(8, 24),
          acc_read_full_width = false, // set to true to output fp32
          num_counter = 0,
          dataflow = Dataflow.WS,
          ex_read_from_acc = false,
          ex_write_to_spad = false,
          has_max_pool = false,
          use_tl_ext_mem = true,
          sp_singleported = false,
          spad_read_delay = 4,
          use_shared_ext_mem = true,
          acc_sub_banks = 1,
          has_normalizations = false,
          meshRows = dim,
          meshColumns = dim,
          tile_latency = 0,
          mesh_output_delay = 1,
          acc_latency = 3,
          dma_maxbytes = site(CacheBlockBytes),
          dma_buswidth = site(CacheBlockBytes),
          tl_ext_mem_base = smKey.address,
          sp_banks = smKey.numBanks,
          sp_capacity = CapacityInKilobytes(smKey.size >> 10),
          acc_capacity = CapacityInKilobytes(accSizeInKB),
        )
      },
      tileId = idOffset,
      tileSize = Left(tileSize),
      slaveAddress = smKey.address + smKey.size + 0x3000,
      scalingFactorMem = Some(GemminiScalingFactorMemConfig(
        sizeInBytes = 16 << 10,
        sramLineSizeInBytes = 256 / 8,
        logicalLineSizeInBytes = 512 / 8,
      ))
    )
    Seq(GemminiTileAttachParams(
      tileParams,
      crossing
    )) ++ prev
  }
  case NumTiles => up(NumTiles) + 1
}) {
  def this(location: HierarchicalLocation, dim: Int, accSizeInKB: Int, tileSize: (Int, Int, Int)) =
    this(location, RocketCrossingParams(
      master = HierarchicalElementMasterPortParams.locationDefault(location),
      slave = HierarchicalElementSlavePortParams.locationDefault(location),
      mmioBaseAddressPrefixWhere = location match {
        case InSubsystem => CBUS
        case InCluster(clusterId) => CCBUS(clusterId)
      }
    ), dim, accSizeInKB, tileSize)
}

class WithRadianceSharedMem(address: BigInt,
                            size: Int,
                            numBanks: Int,
                            numWords: Int,
                            memType: MemType = TwoPort,
                            strideByWord: Boolean = true,
                            filterAligned: Boolean = true,
                            disableMonitors: Boolean = true,
                            serialization: RadianceSmemSerialization = FullySerialized
                           ) extends Config((_, _, _) => {
  case RadianceSharedMemKey => {
    require(isPow2(size) && size >= 1024)
    Some(RadianceSharedMemKey(
      address, size, numBanks, numWords, 4, 2, memType,
      strideByWord, filterAligned, disableMonitors, serialization
    ))
  }
})

class WithRadianceFrameBuffer(baseAddress: BigInt,
                              width: Int,
                              size: Int,
                              validAddress: BigInt,
                              fbName: String = "fb") extends Config((_, _, up) => {
  case RadianceFrameBufferKey => {
    up(RadianceFrameBufferKey) ++ Seq(
      RadianceFrameBufferKey(baseAddress, width, size, validAddress, fbName)
    )
  }
})

class WithRadianceCluster(
  clusterId: Int,
  location: HierarchicalLocation = InSubsystem,
  crossing: RocketCrossingParams = RocketCrossingParams(),
  smemConfig: RadianceSharedMemKey,
  l1Config: DCacheParams,
) extends Config((site, here, up) => {
  case ClustersLocated(`location`) => {
    val baseAddress = x"4000_0000" + x"4_0000" * clusterId
    up(ClustersLocated(location)) :+ RadianceClusterAttachParams(
      RadianceClusterParams(
        clusterId = clusterId,
        baseAddr = baseAddress,
        smemConfig = smemConfig.copy(address = baseAddress + smemConfig.address),
        l1Config = l1Config,
      ),
      crossing)
  }
  case TLNetworkTopologyLocated(InCluster(`clusterId`)) => List(
    RadianceClusterBusTopologyParams(
      clusterId = clusterId,
      csbus = site(SystemBusKey),
      ccbus = site(ControlBusKey).copy(errorDevice = None),
      coherence = site(ClusterBankedCoherenceKey(clusterId))
    )
  )
  case PossibleTileLocations => up(PossibleTileLocations) :+ InCluster(clusterId)
})

class WithSIMTConfig(numWarps: Int = 4, numLanes: Int = 4, numLsuLanes: Int = 4, numSMEMInFlights: Int = 8)
extends Config((site, _, up) => {
  case SIMTCoreKey => {
    Some(up(SIMTCoreKey).getOrElse(SIMTCoreParams()).copy(
      numWarps = numWarps,
      numLanes = numLanes,
      numLsuLanes = numLsuLanes,
      numSMEMInFlights = numSMEMInFlights
    ))
  }
})

class WithMemtraceCore(tracefilename: String, traceHasSource: Boolean = false)
extends Config((site, _, up) => {
  case MemtraceCoreKey => {
    require(
      site(SIMTCoreKey).isDefined,
      "Memtrace core requires a SIMT configuration. Use WithNLanes to enable SIMT."
    )
    Some(MemtraceCoreParams(tracefilename, traceHasSource))
  }
  case SubsystemInjectorKey => up(SubsystemInjectorKey) + MemtraceInjector
})

// When `enable` is false, we still elaborate Coalescer, but it acts as a
// pass-through logic that always outputs un-coalesced requests.  This is
// useful for when we want to keep the generated wire and net names the same
// to e.g. compare waveforms.
class WithCoalescer(nNewSrcIds: Int = 8, enable : Boolean = true) extends Config((site, _, up) => {
  case CoalescerKey => {
    val (nLanes, numOldSrcIds) = up(SIMTCoreKey) match {
      case Some(param) => (param.numLsuLanes, param.numSMEMInFlights)
      case None => (1,1)
    }

    val sbusWidthInBytes = site(SystemBusKey).beatBytes
    // FIXME: coalescer fails to instantiate with 4-byte bus
    require(sbusWidthInBytes > 2,
      "FIXME: coalescer currently doesn't instantiate with 4-byte sbus")

    // If instantiating L1 cache, the maximum coalescing size should match the
    // cache line size
    val maxCoalSizeInBytes = up(VortexL1Key) match {
      case Some(param) => param.inputSize
      case None => sbusWidthInBytes
    }
      
    // Note: this config chooses a single-sized coalescing logic by default.
    Some(DefaultCoalescerConfig.copy(
      enable       = enable,
      numLanes     = nLanes,
      numOldSrcIds = numOldSrcIds,
      numNewSrcIds = nNewSrcIds,
      addressWidth = 32, // FIXME hardcoded as 32-bit system
      dataBusWidth = log2Ceil(maxCoalSizeInBytes),
      coalLogSize = log2Ceil(maxCoalSizeInBytes)
      )
    )
  }
})

class WithExtGPUMem(address: BigInt = x"1_0000_0000",
                    size: BigInt = x"8000_0000") extends Config((site, here, up) => {
  case GPUMemory => Some(GPUMemParams(address, size))
  case ExtMem => up(ExtMem).map(x => {
    val gap = address - x.master.base - x.master.size
    x.copy(master = x.master.copy(size = x.master.size + gap + size))
  })
})
case class GPUMemParams(address: BigInt = BigInt("0x100000000", 16), size: BigInt = 0x80000000)
case object GPUMemory extends Field[Option[GPUMemParams]](None)

object RadianceSimArgs extends Field[Option[Boolean]](None)

class WithRadianceSimParams(enabled: Boolean) extends Config((_, _, _) => {
  case RadianceSimArgs => Some(enabled)
})

case class MuonTileAttachParams(
  tileParams: MuonTileParams,
  crossingParams: RocketCrossingParams
) extends CanAttachTile {
  type TileType = MuonTile
}

case class RadianceClusterAttachParams(
  clusterParams: RadianceClusterParams,
  crossingParams: HierarchicalElementCrossingParamsLike
) extends CanAttachCluster {
  type ClusterType = RadianceCluster
}

// cluster local sbus: between muons and l1 (csbus is l1->l2)
case class CLSBUS(clusterId: Int) extends TLBusWrapperLocation(s"clsbus$clusterId")
// cluster local cbus: carries low bw traffic intra cluster (e.g. gemmini cmd)
case class CLCBUS(clusterId: Int) extends TLBusWrapperLocation(s"clcbus$clusterId")

case class RadianceClusterBusTopologyParams(
  clusterId: Int,
  csbus: SystemBusParams,
  ccbus: PeripheryBusParams,
  coherence: BankedCoherenceParams
) extends TLBusWrapperTopology(
  instantiations = List(
    (CSBUS(clusterId), csbus),
    (CLSBUS(clusterId), csbus),
    (CLCBUS(clusterId), RadianceCBusParams(
      beatBytes = ccbus.beatBytes,
      blockBytes = ccbus.beatBytes,
    )),
    (CCBUS(clusterId), RadianceCBusParams(
      beatBytes = ccbus.beatBytes,
      blockBytes = ccbus.blockBytes,
    ))) ++ (if (coherence.nBanks == 0) Nil else List(
    (CMBUS(clusterId), csbus),
    (CCOH (clusterId), CoherenceManagerWrapperParams(csbus.blockBytes, csbus.beatBytes, coherence.nBanks, CCOH(clusterId).name)(coherence.coherenceManager)))),
  connections = if (coherence.nBanks == 0) Nil else List(
    (CSBUS(clusterId), CCOH (clusterId), TLBusWrapperConnection(driveClockFromMaster = Some(true), nodeBinding = BIND_STAR)()),
    (CCOH (clusterId), CMBUS(clusterId), TLBusWrapperConnection.crossTo(
      xType = NoCrossing,
      driveClockFromMaster = Some(true),
      nodeBinding = BIND_QUERY))
  )
)
