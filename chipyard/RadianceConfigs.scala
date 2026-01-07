package chipyard

import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.rocket.DCacheParams
import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.Config
import radiance.subsystem._
import radiance.unittest.WithMemPerfMuonTileReplacement
import testchipip.soc.OBUS

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
  new freechips.rocketchip.subsystem.WithCacheBlockBytes(32) ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(1) ++
  new freechips.rocketchip.subsystem.WithEdgeDataBits(64) ++

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

class WithRadianceTapeoutPeripherals extends Config(
  new testchipip.soc.WithChipIdPin ++                               // Add pin to identify chips
  new chipyard.harness.WithSerialTLTiedOff(tieoffs=Some(Seq(1))) ++ // Tie-off the chip-to-chip link in single-chip sims
  new testchipip.serdes.WithSerialTL(Seq(
    testchipip.serdes.SerialTLParams(                               // 0th serial-tl is chip-to-bringup-fpga
      manager = Some(testchipip.serdes.SerialTLManagerParams(       // port acts as a manager of offchip memory
        memParams = Seq(testchipip.serdes.ManagerRAMParams(         // 4 GB of off-chip memory
          address = BigInt("80000000", 16),
          size    = BigInt("100000000", 16)
        )),
        isMemoryDevice = true,
        slaveWhere = MBUS
      )),
      client = Some(testchipip.serdes.SerialTLClientParams()),      // bringup serial-tl acts only as a client
      phyParams = testchipip.serdes.DecoupledExternalSyncSerialPhyParams(
        phitWidth = 32,
        flitWidth = 32,
      ),  // bringup serial-tl is sync'd to external clock
    ),
    testchipip.serdes.SerialTLParams(                               // 1st serial-tl is chip-to-chip
      client = Some(testchipip.serdes.SerialTLClientParams()),      // chip-to-chip serial-tl acts as a client
      manager = Some(testchipip.serdes.SerialTLManagerParams(       // chip-to-chip serial-tl managers other chip's memory
        memParams = Seq(testchipip.serdes.ManagerRAMParams(
          address = 0,
          size = 1L << 33,
        )),
        slaveWhere = OBUS
      )),
      phyParams = testchipip.serdes.CreditedSourceSyncSerialPhyParams(phitWidth=16) // narrow link
    ),
  )) ++
  new testchipip.soc.WithOffchipBusClient(MBUS,       // obus provides path to other chip's memory, and also backs mbus
    blockRange = Seq(AddressSet(0, (1L << 33) - 1)),  // The lower 8GB is mapped to this chip
    replicationBase = Some(1L << 33)                  // The upper 8GB goes off-chip
  ) ++
  new testchipip.soc.WithOffchipBus ++
  new freechips.rocketchip.subsystem.WithNoMemPort ++
  new WithRadianceSimParams(false) ++
  new freechips.rocketchip.subsystem.WithClockGateModel("/vsrc/TSMCCGWrapper.v")
)

class RadianceBringupHostConfig extends Config(
  //=============================
  // Set up TestHarness for standalone-sim
  //=============================
  new chipyard.harness.WithAbsoluteFreqHarnessClockInstantiator ++  // Generate absolute frequencies
  new chipyard.harness.WithSerialTLTiedOff ++                       // when doing standalone sim, tie off the serial-tl port
  new chipyard.harness.WithSimTSIToUARTTSI ++                       // Attach SimTSI-over-UART to the UART-TSI port
  new chipyard.iobinders.WithSerialTLPunchthrough ++                // Don't generate IOCells for the serial TL (this design maps to FPGA)
  new testchipip.serdes.WithSerialTL(Seq(testchipip.serdes.SerialTLParams(
    manager = Some(testchipip.serdes.SerialTLManagerParams(
      memParams = Seq(testchipip.serdes.ManagerRAMParams(           // Bringup platform can access all memory from 0 to DRAM_BASE
        address = BigInt("00000000", 16),
        size    = BigInt("80000000", 16)
      ))
    )),
    client = Some(testchipip.serdes.SerialTLClientParams()),        // Allow chip to access this device's memory (DRAM)
    phyParams = testchipip.serdes.DecoupledInternalSyncSerialPhyParams(phitWidth=32, flitWidth=32, freqMHz = 75) // bringup platform provides the clock
  ))) ++
  new testchipip.soc.WithOffchipBusClient(SBUS,                     // offchip bus hangs off the SBUS
    blockRange = AddressSet.misaligned(0x80000000L, (BigInt(1) << 30) * 4)) ++ // offchip bus should not see the main memory of the testchip, since that can be accessed directly
  new testchipip.soc.WithOffchipBus ++                                         // offchip bus
  new freechips.rocketchip.subsystem.WithExtMemSize((1 << 30) * 4L) ++         // match what the chip believes the max size should be
  new testchipip.tsi.WithUARTTSIClient(initBaudRate = BigInt(921600)) ++       // nonstandard baud rate to improve performance
  new chipyard.clocking.WithPassthroughClockGenerator ++ // pass all the clocks through, since this isn't a chip
  new chipyard.config.WithUniformBusFrequencies(75.0) ++   // run all buses of this system at 75 MHz
  new chipyard.NoCoresConfig)

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
  new WithRadianceMxGemmini(location = InCluster(1), dim = 16, accSizeInKB = 32, tileSize = (8, 8, 8)) ++
  new WithMuonCores(2, location = InCluster(1), l0i = Some(L0iCacheConfig), l0d = Some(L0dCacheConfig)) ++
  new WithRadianceCluster(1, smemConfig = TapeoutSmemConfig, l1Config = L1CacheConfig) ++
  // new WithRadianceMxGemmini(location = InCluster(0), dim = 16, accSizeInKB = 32, tileSize = (8, 8, 8)) ++
  new WithMuonCores(2, location = InCluster(0), l0i = Some(L0iCacheConfig), l0d = Some(L0dCacheConfig)) ++
  new WithRadianceCluster(0, smemConfig = TapeoutSmemConfig, l1Config = L1CacheConfig) ++
  new WithExtGPUMem() ++
  new freechips.rocketchip.rocket.WithCFlushEnabled ++ // thanks kevin
  new freechips.rocketchip.rocket.WithNSmallCores(1) ++
  new RadianceBaseConfig
)

class RadianceGemminiOnlyConfig extends Config(
  new WithRadianceMxGemmini(location = InCluster(0), dim = 16, accSizeInKB = 32, tileSize = (8, 8, 8)) ++
  new WithRadianceCluster(0, smemConfig = TapeoutSmemConfig, l1Config = L1CacheConfig) ++
  new WithExtGPUMem() ++
  new freechips.rocketchip.rocket.WithNSmallCores(1) ++
  new RadianceBaseConfig
)

class TetheredRadianceTapeoutConfig extends Config(
  new chipyard.harness.WithAbsoluteFreqHarnessClockInstantiator ++   // use absolute freqs for sims in the harness
  new chipyard.harness.WithMultiChipSerialTL(0, 1) ++                // connect the serial-tl ports of the chips together
  new chipyard.harness.WithMultiChip(0, new Config(
    new WithRadianceTapeoutPeripherals ++
    new RadianceTapeoutSimConfig)
  ) ++ // ChipTop0 is the design-to-be-taped-out
  new chipyard.harness.WithMultiChip(1, new RadianceBringupHostConfig))  // ChipTop1 is the bringup design

class RadianceClusterConfig extends Config(
  new WithRadianceGemmini(location = InCluster(0), dim = 16, accSizeInKB = 16, tileSize = Right(8), hasAccSlave = false) ++
  new WithMuonCores(2, location = InCluster(0)) ++
  new WithRadianceCluster(0, smemConfig = TapeoutSmemConfig, l1Config = L1CacheConfig) ++
  new WithExtGPUMem() ++
  new RadianceBaseConfig)

class RadianceMemPerfConfig extends Config(
  new WithMemPerfMuonTileReplacement(InCluster(0)) ++
  new WithMuonCores(1, location = InCluster(0), l0i = Some(L0iCacheConfig), l0d = Some(L0dCacheConfig)) ++
  new WithRadianceCluster(0, smemConfig = TapeoutSmemConfig, l1Config = L1CacheConfig) ++
  new RadianceBaseConfig
)
