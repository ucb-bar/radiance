package chipyard

import chipyard.stage.phases.TargetDirKey
import freechips.rocketchip.devices.tilelink.BootROMLocated
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.Config
import radiance.subsystem._
import radiance.virgo._

// ----------------
// Radiance Configs
// ----------------

class RadianceBaseConfig extends Config(
  new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16, numSMEMInFlights = 8) ++
  new chipyard.config.WithSystemBusWidth(bitWidth = 256) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt("80000000", 16)) ++
//  new chipyard.config.WithRadBootROM() ++
  new WithRadianceSimParams(true) ++
  new freechips.rocketchip.subsystem.WithCacheBlockBytes(64) ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(2) ++
  new freechips.rocketchip.subsystem.WithEdgeDataBits(256) ++
  new WithRadianceControlBus ++

  new chipyard.config.WithPeripheryBusFrequency(500.0) ++
  new chipyard.config.WithMemoryBusFrequency(500.0) ++
  new chipyard.config.WithControlBusFrequency(500.0) ++
  new chipyard.config.WithSystemBusFrequency(500.0) ++
  new chipyard.config.WithFrontBusFrequency(500.0) ++
  new chipyard.config.WithOffchipBusFrequency(500.0) ++
  new chipyard.harness.WithHarnessBinderClockFreqMHz(500.0) ++
  new chipyard.config.AbstractConfig)

object tapeoutSmemConfig extends RadianceSharedMemKey(
  address = 0, size = 128 << 10, numBanks = 4, numWords = 16, wordSize = 4,
  prealignBufDepth = 2, filterAligned = false, serialization = CoreSerialized
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
  new WithRadianceCluster(0, smemConfig = tapeoutSmemConfig) ++
  new RadianceBaseConfig)

class RadianceCyclotronConfig extends Config(
  new WithCyclotronCores(1) ++
  new WithCoalescer(nNewSrcIds = 16) ++
  new WithVortexL1Banks(nBanks = 8) ++
  new WithRadianceCluster(0, smemConfig = tapeoutSmemConfig) ++
  new RadianceBaseConfig)

class RadianceTapeoutConfig extends Config(
  new WithRadianceGemmini(location = InCluster(1), dim = 16, accSizeInKB = 16, tileSize = Right(8), hasAccSlave = false) ++
  new WithMuonCores(2, location = InCluster(1)) ++
  new WithRadianceCluster(1, smemConfig = tapeoutSmemConfig) ++
  new WithRadianceGemmini(location = InCluster(0), dim = 16, accSizeInKB = 16, tileSize = Right(8), hasAccSlave = false) ++
  new WithMuonCores(2, location = InCluster(0)) ++
  new WithRadianceCluster(0, smemConfig = tapeoutSmemConfig) ++
  new WithExtGPUMem() ++
  new freechips.rocketchip.rocket.WithNSmallCores(1) ++
  new RadianceBaseConfig)

class RadianceClusterConfig extends Config(
  new WithRadianceGemmini(location = InCluster(0), dim = 16, accSizeInKB = 16, tileSize = Right(8), hasAccSlave = false) ++
  new WithMuonCores(2, location = InCluster(0)) ++
  new WithRadianceCluster(0, smemConfig = tapeoutSmemConfig) ++
  new WithExtGPUMem() ++
  new RadianceBaseConfig)

class RadianceClusterSynConfig extends Config(
  new WithRadianceSimParams(false) ++
  new RadianceClusterConfig)
