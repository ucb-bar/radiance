package chipyard

import chipyard.stage.phases.TargetDirKey
import freechips.rocketchip.devices.tilelink.BootROMLocated
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.rocket.DCacheParams
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.Config
import radiance.subsystem._
import radiance.virgo._
import testchipip.serdes.TLSerdesser

// ----------------
// Radiance Configs
// ----------------

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

object l1CacheConfig extends DCacheParams(
  nSets = 512,
  nWays = 4,
  rowBits = 32 * 8,
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
  new WithRadianceCluster(0, smemConfig = tapeoutSmemConfig, l1Config = l1CacheConfig) ++
  new RadianceBaseConfig)

class RadianceCyclotronConfig extends Config(
  new WithCyclotronCores(1) ++
  new WithCoalescer(nNewSrcIds = 16) ++
  new WithVortexL1Banks(nBanks = 8) ++
  new WithRadianceCluster(0, smemConfig = tapeoutSmemConfig, l1Config = l1CacheConfig) ++
  new RadianceBaseConfig)

class RadianceTapeoutConfig extends Config(
  new WithRadianceGemmini(location = InCluster(1), dim = 16, accSizeInKB = 16, tileSize = Right(8), hasAccSlave = false) ++
  new WithMuonCores(2, location = InCluster(1)) ++
  new WithRadianceCluster(1, smemConfig = tapeoutSmemConfig, l1Config = l1CacheConfig) ++
  new WithRadianceGemmini(location = InCluster(0), dim = 16, accSizeInKB = 16, tileSize = Right(8), hasAccSlave = false) ++
  new WithMuonCores(2, location = InCluster(0)) ++
  new WithRadianceCluster(0, smemConfig = tapeoutSmemConfig, l1Config = l1CacheConfig) ++
  new WithExtGPUMem() ++
  new freechips.rocketchip.rocket.WithNSmallCores(1) ++
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
    client = Some(testchipip.serdes.SerialTLClientParams()),
    phyParams = testchipip.serdes.DecoupledExternalSyncSerialPhyParams(phitWidth=1, flitWidth=16),
    bundleParams = TLSerdesser.STANDARD_TLBUNDLE_PARAMS.copy(
      dataBits = 256
    )
  )
  )) ++
  new freechips.rocketchip.subsystem.WithNoMemPort ++

  new RadianceBaseConfig)

class RadianceClusterConfig extends Config(
  new WithRadianceGemmini(location = InCluster(0), dim = 16, accSizeInKB = 16, tileSize = Right(8), hasAccSlave = false) ++
  new WithMuonCores(2, location = InCluster(0)) ++
  new WithRadianceCluster(0, smemConfig = tapeoutSmemConfig, l1Config = l1CacheConfig) ++
  new WithExtGPUMem() ++
  new RadianceBaseConfig)

class RadianceClusterSynConfig extends Config(
  new WithRadianceSimParams(false) ++
  new RadianceClusterConfig)
