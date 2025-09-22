package chipyard

import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.Config
import radiance.subsystem._
import radiance.virgo._

// ----------------
// Virgo Configs
// ----------------

class VirgoConfig extends VirgoClusterConfig
class VirgoFP16Config extends VirgoFP16ClusterConfig
class VirgoHopperConfig extends Virgo4CFP16ClusterConfig
class VirgoFlashConfig extends VirgoClusterConfig
class VirgoSynConfig extends VirgoClusterSynConfig
class VirgoFP16SynConfig extends VirgoFP16ClusterSynConfig
class VirgoHopperSynConfig extends Virgo4CFP16ClusterSynConfig

class VirgoBaseConfig extends Config(
  // NOTE: when changing these, remember to change NUM_CORES/THREADS/WARPS in
  // the verilog source as well!
  new WithSIMTConfig(numWarps = 8, numLanes = 8, numLsuLanes = 8, numSMEMInFlights = 32) ++
  new chipyard.config.WithSystemBusWidth(bitWidth = 256) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt("80000000", 16)) ++
  new chipyard.config.WithRadBootROM() ++
  new WithRadianceSimParams(true) ++
  new freechips.rocketchip.subsystem.WithCacheBlockBytes(64) ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(2) ++
  new freechips.rocketchip.subsystem.WithEdgeDataBits(256) ++

  new chipyard.config.WithPeripheryBusFrequency(400.0) ++
  new chipyard.config.WithMemoryBusFrequency(400.0) ++
  new chipyard.config.WithControlBusFrequency(400.0) ++
  new chipyard.config.WithSystemBusFrequency(400.0) ++
  new chipyard.config.WithFrontBusFrequency(400.0) ++
  new chipyard.config.WithOffchipBusFrequency(400.0) ++
  new chipyard.harness.WithHarnessBinderClockFreqMHz(400.0) ++
  new chipyard.config.AbstractConfig)

class VirgoFP16ClusterConfig extends Config(
  new WithRadianceGemmini(location = InCluster(0), dim = 16, accSizeInKB = 32, tileSize = (8, 4, 8), dataType = RadianceGemminiDataType.FP16) ++
  new WithVortexCores(8, location = InCluster(0), tensorCoreFP16 = true, tensorCoreDecoupled = false, useVxCache = false) ++
  new WithRadianceSharedMem(address = x"ff000000", size = 128 << 10, numBanks = 4, numWords = 16) ++
  new WithCoalescer(nNewSrcIds = 16) ++
  new WithVortexL1Banks(nBanks = 8) ++
  new WithVirgoCluster(0) ++
  new VirgoBaseConfig)

class Virgo8B8WFP16ClusterConfig extends Config(
  new WithRadianceGemmini(location = InCluster(0), dim = 16, accSizeInKB = 32, tileSize = (8, 4, 8), dataType = RadianceGemminiDataType.FP16) ++
  new WithVortexCores(8, location = InCluster(0), tensorCoreFP16 = true, tensorCoreDecoupled = false, useVxCache = false) ++
  new WithRadianceSharedMem(address = x"ff000000", size = 128 << 10, numBanks = 8, numWords = 8) ++
  new WithCoalescer(nNewSrcIds = 16) ++
  new WithVortexL1Banks(nBanks = 8) ++
  new WithVirgoCluster(0) ++
  new VirgoBaseConfig)

class Virgo4CFP16ClusterConfig extends Config(
  new WithRadianceGemmini(location = InCluster(0), dim = 16, accSizeInKB = 32, tileSize = (8, 4, 8), dataType = RadianceGemminiDataType.FP16) ++
  new WithVortexCores(4, location = InCluster(0), tensorCoreFP16 = true, tensorCoreDecoupled = true, useVxCache = false) ++
  // new radiance.subsystem.WithRadianceSharedMem(address = x"ff000000", size = 128 << 10, numBanks = 4, numWords = 16,
  //                                              memType = radiance.subsystem.TwoReadOneWrite,
  //                                              serializeUnaligned = radiance.subsystem.CoreSerialized) ++
  // NOTE: Hopper Tensor Core does not work with 16-word config due to the
  // address alignment requirement
  new WithRadianceSharedMem(address = x"ff000000", size = 128 << 10, numBanks = 4, numWords = 8) ++
  new WithCoalescer(nNewSrcIds = 16) ++
  new WithVortexL1Banks(nBanks = 8) ++
  new WithVirgoCluster(0) ++
  new VirgoBaseConfig)

class VirgoClusterConfig extends Config(
  // important to keep gemmini tile before RadianceCores to ensure radiance tile id is 0-indexed
  new WithRadianceGemmini(location = InCluster(0), dim = 8, accSizeInKB = 16, tileSize = 8) ++
  new WithVortexCores(4, location = InCluster(0), tensorCoreFP16 = false, tensorCoreDecoupled = false, useVxCache = false) ++
  // new radiance.subsystem.WithRadianceFrameBuffer(x"ff018000", 16, 0x8000, x"ff011000", "fb0") ++
  new WithRadianceSharedMem(address = x"ff000000", size = 256 << 10/*KBytes*/, numBanks = 8, numWords = 8,
                                               // memType = radiance.subsystem.TwoReadOneWrite,
                                               serialization = CoreSerialized) ++
  new WithCoalescer(nNewSrcIds = 16) ++
  new WithVortexL1Banks(nBanks = 8) ++
  new WithVirgoCluster(0) ++
  new VirgoBaseConfig)

class VirgoClusterSmem16KConfig extends Config(
  new WithRadianceGemmini(location = InCluster(0), dim = 8, accSizeInKB = 4, tileSize = 4) ++
  new WithVortexCores(4, location = InCluster(0), useVxCache = false) ++
  new WithRadianceSharedMem(address = x"ff000000", size = 16 << 10/*KBytes*/, numBanks = 4, numWords = 8) ++ // serializeUnaligned: false
  new WithCoalescer(nNewSrcIds = 16) ++
  new WithVortexL1Banks(nBanks = 8)++
  new WithVirgoCluster(0) ++
  new VirgoBaseConfig)

class VirgoTwoClustersSmem16KConfig extends Config(
  new WithRadianceGemmini(location = InCluster(0), dim = 8, accSizeInKB = 4, tileSize = 4) ++
  new WithVortexCores(2, location = InCluster(0), useVxCache = false) ++
  new WithRadianceGemmini(location = InCluster(1), dim = 8, accSizeInKB = 4, tileSize = 4) ++
  new WithVortexCores(2, location = InCluster(1), useVxCache = false) ++
  new WithRadianceSharedMem(address = x"ff000000", size = 16 << 10, numBanks = 4, numWords = 8) ++
  new WithCoalescer(nNewSrcIds = 16) ++
  new WithVortexL1Banks(nBanks = 8)++
  new WithVirgoCluster(0) ++
  new WithVirgoCluster(1) ++
  new VirgoBaseConfig)

class VirgoBigLittleClusterConfig extends Config(
  new WithRadianceGemmini(location = InCluster(0), dim = 4, accSizeInKB = 16, tileSize = 16) ++
  new WithRadianceGemmini(location = InCluster(0), dim = 8, accSizeInKB = 16, tileSize = 8) ++
  new WithVortexCores(2, location = InCluster(0), useVxCache = false) ++
  new WithRadianceSharedMem(address = x"ff000000", size = 64 << 10, numBanks = 4, numWords = 8) ++
  new WithCoalescer(nNewSrcIds = 16) ++
  new WithVortexL1Banks(nBanks = 8)++
  new WithVirgoCluster(0) ++
  new VirgoBaseConfig)

class VirgoClusterSynConfig extends Config(
  new WithRadianceSimParams(false) ++
  new VirgoClusterConfig)

class VirgoFP16ClusterSynConfig extends Config(
  new WithRadianceSimParams(false) ++
  new VirgoFP16ClusterConfig)

class Virgo4CFP16ClusterSynConfig extends Config(
  new WithRadianceSimParams(false) ++
  new Virgo4CFP16ClusterConfig)

class VirgoBigLittleClusterSynConfig extends Config(
  new WithRadianceSimParams(false) ++
  new VirgoBigLittleClusterConfig)

class VirgoNoCacheConfig extends Config(
  new WithVortexCores(1, useVxCache = false) ++
  new WithCoalescer(nNewSrcIds = 8) ++
  new VirgoBaseConfig)

class VirgoNoCoalConfig extends Config(
  new WithVortexCores(1, useVxCache = false) ++
  new WithVortexL1Banks(nBanks = 1)++
  new VirgoBaseConfig)

class VirgoFuzzerConfig extends Config(
  new WithFuzzerCores(1, useVxCache = false) ++
  new WithCoalescer(nNewSrcIds = 2) ++
  new WithSIMTConfig(numLsuLanes = 4, numSMEMInFlights = 2) ++
  new chipyard.config.WithSystemBusWidth(bitWidth = 256) ++
  new chipyard.config.AbstractConfig)
