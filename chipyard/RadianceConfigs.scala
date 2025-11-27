package chipyard

import chipyard.stage.phases.TargetDirKey
import freechips.rocketchip.devices.tilelink.BootROMLocated
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.rocket.DCacheParams
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.Config
import radiance.subsystem._
import radiance.unittest.WithMemPerfMuonTileReplacement
import radiance.virgo._
import testchipip.serdes.TLSerdesser

// ----------------
// Radiance Configs
// ----------------

class WithNoMbusScratchpad extends Config((site, here, up) => {
  case testchipip.soc.BankedScratchpadKey => Seq()
})

class RadianceBaseConfig extends Config(
  new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16, numSMEMInFlights = 8) ++
  new chipyard.config.WithSystemBusWidth(bitWidth = 256) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(x"1_0000_0000") ++
//  new chipyard.config.WithRadBootROM() ++
  new WithRadianceSimParams(true) ++
  new freechips.rocketchip.subsystem.WithCacheBlockBytes(32) ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(2) ++
  new freechips.rocketchip.subsystem.WithEdgeDataBits(256) ++

  new WithRadianceControlBus ++
  new WithNoMbusScratchpad ++

  new chipyard.config.WithPeripheryBusFrequency(500.0) ++
  new chipyard.config.WithMemoryBusFrequency(500.0) ++
  new chipyard.config.WithControlBusFrequency(500.0) ++
  new chipyard.config.WithSystemBusFrequency(500.0) ++
  new chipyard.config.WithFrontBusFrequency(500.0) ++
  new chipyard.config.WithOffchipBusFrequency(500.0) ++
  new chipyard.harness.WithHarnessBinderClockFreqMHz(500.0) ++
  new chipyard.config.AbstractConfig)

object TapeoutSmemConfig extends RadianceSharedMemKey(
  address = 0, size = 128 << 10, numBanks = 4, numWords = 16, wordSize = 4,
  prealignBufDepth = 2, filterAligned = false, serialization = CoreSerialized
)

object L0iCacheConfig extends DCacheParams(
  nSets = 128,
  nWays = 1,
  rowBits = 32 * 8,
  blockBytes = 32,
  nMSHRs = 8,
)

object L0dCacheConfig extends DCacheParams(
  nSets = 512,
  nWays = 1,
  rowBits = 64 * 8,
  blockBytes = 64,
  nMSHRs = 8,
)

object L1CacheConfig extends DCacheParams(
  nSets = 512,
  nWays = 4,
  rowBits = 32 * 8, // physical (sram) size
  blockBytes = 32, // logical size
  nMSHRs = 8,
)

class WithRadianceControlBus extends Config ((site, here, up) => {
  case ControlBusKey => up(ControlBusKey).copy(
    beatBytes = 8,
//    atomics = None, // TODO pending rocket chip PR
  )
  // this bus key propagates to ccbus and clcbus, might need to split off
})

class RadianceMuonConfig extends Config(
  new WithMuonCores(1, location = InCluster(0)) ++
  new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16, numSMEMInFlights = 4) ++
  new WithRadianceCluster(0, smemConfig = TapeoutSmemConfig, l1Config = L1CacheConfig) ++
  new RadianceBaseConfig)

/*
class RadianceCyclotronConfig extends Config(
  new WithCyclotronCores(1) ++
  new WithCoalescer(nNewSrcIds = 16) ++
  new WithVortexL1Banks(nBanks = 8) ++
  new WithRadianceCluster(0, smemConfig = tapeoutSmemConfig, l1Config = l1CacheConfig) ++
  new RadianceBaseConfig)
*/

class RadianceTapeoutSimConfig extends Config(
  // new WithRadianceMxGemmini(location = InCluster(1), dim = 16, accSizeInKB = 128, tileSize = (8, 8, 8)) ++
  new WithMuonCores(2, location = InCluster(1), l0i = Some(L0iCacheConfig), l0d = Some(L0dCacheConfig)) ++
  new WithRadianceCluster(1, smemConfig = TapeoutSmemConfig, l1Config = L1CacheConfig) ++
  // new WithRadianceMxGemmini(location = InCluster(0), dim = 16, accSizeInKB = 128, tileSize = (8, 8, 8)) ++
  new WithMuonCores(2, location = InCluster(0), l0i = Some(L0iCacheConfig), l0d = Some(L0dCacheConfig)) ++
  new WithRadianceCluster(0, smemConfig = TapeoutSmemConfig, l1Config = L1CacheConfig) ++
  new WithExtGPUMem() ++
  new freechips.rocketchip.rocket.WithCFlushEnabled ++ // thanks kevin
  new freechips.rocketchip.rocket.WithNSmallCores(1) ++
  new RadianceBaseConfig
)

// note: this config might throw assertions due to tlserdesser source id implementation?
// so use sim config for now
class RadianceTapeoutConfig extends Config(
  new testchipip.serdes.WithSerialTL(Seq(testchipip.serdes.SerialTLParams(
    manager = Some(
      testchipip.serdes.SerialTLManagerParams(
        memParams = Seq(testchipip.serdes.ManagerRAMParams(
          address = BigInt("80000000", 16),
          size    = BigInt("100000000", 16)
        )),
        isMemoryDevice = true,
        slaveWhere = MBUS
      )
    ),
    client = Some(testchipip.serdes.SerialTLClientParams()), // TODO: override id bits here?
    phyParams = testchipip.serdes.DecoupledExternalSyncSerialPhyParams(phitWidth=32, flitWidth=32),
    bundleParams = TLSerdesser.STANDARD_TLBUNDLE_PARAMS.copy(
      dataBits = 256
    )
  ))) ++
  new freechips.rocketchip.subsystem.WithNoMemPort ++
  new freechips.rocketchip.subsystem.WithClockGateModel("/vsrc/TSMCCGWrapper.v") ++
  new RadianceTapeoutSimConfig)

class RadianceClusterConfig extends Config(
  new WithRadianceGemmini(location = InCluster(0), dim = 16, accSizeInKB = 16, tileSize = Right(8), hasAccSlave = false) ++
  new WithMuonCores(2, location = InCluster(0)) ++
  new WithRadianceCluster(0, smemConfig = TapeoutSmemConfig, l1Config = L1CacheConfig) ++
  new WithExtGPUMem() ++
  new RadianceBaseConfig)

class RadianceClusterSynConfig extends Config(
  new WithRadianceSimParams(false) ++
  new RadianceClusterConfig)

class RadianceMemPerfConfig extends Config(
  new WithMemPerfMuonTileReplacement(InCluster(0)) ++
  new WithMuonCores(1, location = InCluster(0), l0i = Some(L0iCacheConfig), l0d = Some(L0dCacheConfig)) ++
  new WithRadianceCluster(0, smemConfig = TapeoutSmemConfig, l1Config = L1CacheConfig) ++
  new RadianceBaseConfig
)
