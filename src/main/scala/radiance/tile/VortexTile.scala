// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.tile

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.TileCrossingParamsLike
import freechips.rocketchip.util._
import freechips.rocketchip.prci.ClockSinkParameters
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.tile._
import radiance.memory._

case class VortexTileParams(
    core: VortexCoreParams = VortexCoreParams(),
    useVxCache: Boolean = false,
    icache: Option[ICacheParams] = None /* Some(ICacheParams()) */,
    dcache: Option[DCacheParams] = None /* Some(DCacheParams()) */,
    btb: Option[BTBParams] = None, // Some(BTBParams()),
    dataScratchpadBytes: Int = 0,
    name: Option[String] = Some("vortex_tile"),
    hartId: Int = 0,
    beuAddr: Option[BigInt] = None,
    blockerCtrlAddr: Option[BigInt] = None,
    clockSinkParams: ClockSinkParameters = ClockSinkParameters(),
    boundaryBuffers: Option[RocketTileBoundaryBufferParams] = None
) extends InstantiableTileParams[VortexTile] {
  // require(icache.isDefined)
  // require(dcache.isDefined)

  def instantiate(crossing: TileCrossingParamsLike, lookup: LookupByHartIdImpl)(
      implicit p: Parameters
  ): VortexTile = {
    new VortexTile(this, crossing, lookup)
  }
}

// TODO: move to VortexCore
// VortexTileParams extends TileParams which require a `core: CoreParams`
// field, so VortexCoreParams needs to extend from that, requiring all
// these fields to be initialized.  Most of this is unnecessary though. TODO
case class VortexCoreParams(
    bootFreqHz: BigInt = 0,
    useVM: Boolean = true,
    useUser: Boolean = false,
    useSupervisor: Boolean = false,
    useHypervisor: Boolean = false,
    useDebug: Boolean = true,
    useAtomics: Boolean = false,
    useAtomicsOnlyForIO: Boolean = false,
    useCompressed: Boolean = false,
    useRVE: Boolean = false,
    useSCIE: Boolean = false,
    useBitManip: Boolean = false,
    useBitManipCrypto: Boolean = false,
    useCryptoNIST: Boolean = false,
    useCryptoSM: Boolean = false,
    useConditionalZero: Boolean = false,
    nLocalInterrupts: Int = 0,
    useNMI: Boolean = false,
    nBreakpoints: Int = 1,
    useBPWatch: Boolean = false,
    mcontextWidth: Int = 0,
    scontextWidth: Int = 0,
    nPMPs: Int = 8,
    nPerfCounters: Int = 0,
    haveBasicCounters: Boolean = true,
    haveCFlush: Boolean = false,
    misaWritable: Boolean = true,
    nL2TLBEntries: Int = 0,
    nL2TLBWays: Int = 1,
    nPTECacheEntries: Int = 8,
    mtvecInit: Option[BigInt] = Some(BigInt(0)),
    mtvecWritable: Boolean = true,
    fastLoadWord: Boolean = true,
    fastLoadByte: Boolean = false,
    branchPredictionModeCSR: Boolean = false,
    clockGate: Boolean = false,
    mvendorid: Int = 0, // 0 means non-commercial implementation
    mimpid: Int = 0x20181004, // release date in BCD
    mulDiv: Option[MulDivParams] = Some(MulDivParams()),
    fpu: Option[FPUParams] = Some(FPUParams()),
    debugROB: Boolean =
      false, // if enabled, uses a C++ debug ROB to generate trace-with-wdata
    haveCease: Boolean = true, // non-standard CEASE instruction
    haveSimTimeout: Boolean = true // add plusarg for simulation timeout
) extends CoreParams {
  val haveFSDirty = false
  val pmpGranularity: Int = if (useHypervisor) 4096 else 4
  val fetchWidth: Int = if (useCompressed) 2 else 1
  val decodeWidth: Int = fetchWidth / (if (useCompressed) 2 else 1)
  val retireWidth: Int = 1
  val instBits: Int = if (useCompressed) 16 else 32
  val lrscCycles: Int = 80 // worst case is 14 mispredicted branches + slop
  val traceHasWdata: Boolean = false // ooo wb, so no wdata in trace
}

class VortexTile private (
    val vortexParams: VortexTileParams,
    crossing: ClockCrossingType,
    lookup: LookupByHartIdImpl,
    q: Parameters
) extends BaseTile(vortexParams, crossing, lookup, q)
    with SinksExternalInterrupts
    with SourcesExternalNotifications {
  // Private constructor ensures altered LazyModule.p is used implicitly
  def this(
      params: VortexTileParams,
      crossing: TileCrossingParamsLike,
      lookup: LookupByHartIdImpl
  )(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  val intOutwardNode = IntIdentityNode()
  val slaveNode = TLIdentityNode()
  val masterNode = visibilityNode

  // Memory-mapped region for HTIF communication
  // We use fixed addresses instead of tohost/fromhost
  val regDevice =
    new SimpleDevice("vortex-reg", Seq(s"vortex-reg${tileParams.hartId}"))
  val regNode = TLRegisterNode(
    address = Seq(AddressSet(0x7c000000 + 0x1000 * tileParams.hartId, 0xfff)),
    device = regDevice,
    beatBytes = 4,
    concurrency = 1
  )

  regNode := TLFragmenter(4, 64) := tlSlaveXbar.node

  // val dmemDevice = new SimpleDevice("dtim", Seq("sifive,dtim0"))
  /*val dmemNode = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(
      address = AddressSet.misaligned(tileParams.dcache.get.scratch.getOrElse(0),
        tileParams.dcache.get.nSets * tileParams.dcache.get.blockBytes),
      resources = dmemDevice.reg("mem"),
      regionType = RegionType.IDEMPOTENT,
      executable = true,
      supportsArithmetic = /*if (usingAtomics) TransferSizes(4, coreDataBytes) else*/ TransferSizes.none,
      supportsLogical = /*if (usingAtomics) TransferSizes(4, coreDataBytes) else*/ TransferSizes.none,
      supportsPutPartial = TransferSizes(1, lazyCoreParamsView.coreDataBytes),
      supportsPutFull = TransferSizes(1, lazyCoreParamsView.coreDataBytes),
      supportsGet = TransferSizes(1, lazyCoreParamsView.coreDataBytes),
      fifoId = Some(0))), // requests handled in FIFO order
    beatBytes = lazyCoreParamsView.coreDataBytes,
    minLatency = 1)))*/

  require(
    p(SIMTCoreKey).isDefined,
    "SIMTCoreKey not defined; make sure to use WithSimtLanes when using VortexTile"
  )
  val numLanes = p(SIMTCoreKey) match {
    case Some(simtParam) => simtParam.nLanes
    case None            => 4
  }

  // CAUTION: imemSourceWidth is dependent on the ibuffer size.  We have to
  // make sure (1 << imemSourceWidth) is smaller than the per-warp ibuffer
  // size; otherwise, more requests than what ibuffer can accommodate can fire,
  // and responses might stall in the downstream.  This migth cause issues when
  // there are also outstanding dmem responses that might get blocked from
  // going back to the core by a previous imem response due to serialization at
  // the narrow tile<->sbus port, leading to a deadlock.
  //
  // This condition should ideally be asserted at elaboration time, but since
  // ibuffer size is set as a hardcoded macro IBUF_SIZE that's uncontrollable
  // from Chisel, there's no easy solution.  We at least don't expose this as a
  // Parameter and leave as a hardcoded value here.
  val imemSourceWidth = 6 // 1 << imemSourceWidth == IBUF_SIZE

  val dmemSourceWidth = p(SIMTCoreKey) match {
    // TODO: respect coalescer newSrcIds
    case Some(simtParam) => log2Ceil(simtParam.nSrcIds)
    case None            => 4
  }
  require(
    dmemSourceWidth >= 4,
    "Setting a small number of sourceIds may cause correctness bug inside " +
      "Vortex core due to synchronization issues in vx_wspawn. " +
      "We recommend setting nSrcIds to at least 16."
  )

  val smemSourceWidth = 4 // FIXME: hardcoded

  // TODO: parametrize
  val numWarps = 4
  val NW_WIDTH = (if (numWarps == 1) 1 else log2Ceil(numWarps))
  val UUID_WIDTH = 44
  val imemTagWidth = UUID_WIDTH + NW_WIDTH
  val LSUQ_TAG_BITS = 4
  val dmemTagWidth = UUID_WIDTH + LSUQ_TAG_BITS
  // dmem and smem shares the same tag width, DCACHE_NOSM_TAG_WIDTH
  val smemTagWidth = dmemTagWidth

  val imemNodes = Seq.tabulate(1) { i =>
    TLClientNode(
      Seq(
        TLMasterPortParameters.v1(
          clients = Seq(
            TLMasterParameters.v1(
              sourceId = IdRange(0, 1 << imemSourceWidth),
              name = s"Vortex Core ${vortexParams.hartId} I-Mem $i",
              requestFifo = true,
              supportsProbe =
                TransferSizes(1, lazyCoreParamsView.coreDataBytes),
              supportsGet = TransferSizes(1, lazyCoreParamsView.coreDataBytes)
            )
          )
        )
      )
    )
  }

  val dmemNodes = Seq.tabulate(numLanes) { i =>
    TLClientNode(
      Seq(
        TLMasterPortParameters.v1(
          clients = Seq(
            TLMasterParameters.v1(
              sourceId = IdRange(0, 1 << dmemSourceWidth),
              name = s"Vortex Core ${vortexParams.hartId} D-Mem Lane $i",
              requestFifo = true,
              supportsProbe =
                TransferSizes(1, lazyCoreParamsView.coreDataBytes),
              supportsGet = TransferSizes(1, lazyCoreParamsView.coreDataBytes),
              supportsPutFull =
                TransferSizes(1, lazyCoreParamsView.coreDataBytes),
              supportsPutPartial =
                TransferSizes(1, lazyCoreParamsView.coreDataBytes)
            )
          )
        )
      )
    )
  }

  val smemNodes = Seq.tabulate(numLanes) { i =>
    TLClientNode(
      Seq(
        TLMasterPortParameters.v1(
          clients = Seq(
            TLMasterParameters.v1(
              sourceId = IdRange(0, 1 << smemSourceWidth),
              name = s"Vortex Core ${vortexParams.hartId} SharedMem Lane $i",
              requestFifo = true,
              supportsProbe =
                TransferSizes(1, lazyCoreParamsView.coreDataBytes),
              supportsGet = TransferSizes(1, lazyCoreParamsView.coreDataBytes),
              supportsPutFull =
                TransferSizes(1, lazyCoreParamsView.coreDataBytes),
              supportsPutPartial =
                TransferSizes(1, lazyCoreParamsView.coreDataBytes)
            )
          )
        )
      )
    )
  }

  // combine outgoing per-lane dmemNode into 1 idenity node
  //
  // NOTE: We need TLWidthWidget here because there might be a data width
  // mismatch between Vortex's per-lane response and the system bus when we
  // don't instantiate either L1 or the coalescer.  This _should_ be optimized
  // out when we instantiate either which should handle data width conversion
  // internally (which it does by... using TLWidthWidget).
  val dmemAggregateNode = TLIdentityNode()
  dmemNodes.foreach { dmemAggregateNode := TLWidthWidget(4) := _ }

  val memNode = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        clients = Seq(
          TLMasterParameters.v1(
            // FIXME: need to also respect imemSourceWidth
            sourceId = IdRange(0, 1 << dmemSourceWidth),
            name = s"Vortex Core ${vortexParams.hartId} Mem Interface",
            requestFifo = true,
            supportsProbe = TransferSizes(16, 16), // FIXME: hardcoded
            supportsGet = TransferSizes(16, 16),
            supportsPutFull = TransferSizes(16, 16),
            supportsPutPartial = TransferSizes(16, 16)
          )
        )
      )
    )
  )

  // Conditionally instantiate memory coalescer
  val coalescerNode = p(CoalescerKey) match {
    case Some(coalescerParam) => {
      val coal = LazyModule(
        new CoalescingUnit(coalescerParam)
      )
      coal.cpuNode :=* dmemAggregateNode
      coal.aggregateNode // N+1 lanes
    }
    case None => dmemAggregateNode
  }

  // Conditionally instantiate L1 cache
  val (icacheNode, dcacheNode): (TLNode, TLNode) = p(VortexL1Key) match {
    case Some(vortexL1Config) => {
      println(
        s"============ Using Vortex L1 cache ================="
      )
      // require(
      //   p(CoalescerKey).isDefined,
      //   "Vortex L1 configuration currently only works when coalescer is also enabled."
      // )

      val icache = LazyModule(new VortexL1Cache(vortexL1Config))
      val dcache = LazyModule(new VortexL1Cache(vortexL1Config))
      // imemNodes.foreach { icache.coresideNode := TLWidthWidget(4) := _ }
      assert(imemNodes.length == 1) // FIXME
      icache.coresideNode := TLWidthWidget(4) := imemNodes(0)
      // dmemNodes go through coalescerNode
      dcache.coresideNode :=* coalescerNode
      (icache.masterNode, dcache.masterNode)
    }
    case None => {
      val imemWideNode = TLIdentityNode()
      assert(imemNodes.length == 1) // FIXME
      imemWideNode := TLWidthWidget(4) := imemNodes(0)
      (imemWideNode, coalescerNode)
    }
  }

  // Instantiate sharedmem banks
  //
  // Instantiate the same number of banks as there are lanes.
  // TODO: parametrize
  val smemBanks = Seq.tabulate(numLanes) { bankId =>
    // Banked-by-word (4 bytes)
    // base for bank 1: ff...000000|01|00
    // mask for bank 1; 00...111111|00|11
    val base = 0xff000000L | (bankId * 4 /*wordSize*/ )
    val mask = 0x00ffffffL ^ ((numLanes - 1) * 4 /*wordSize*/ )
    LazyModule(new TLRAM(AddressSet(base, mask), beatBytes = 4 /*wordSize*/ ))
  }
  // smem lanes-to-banks crossbar
  val smemXbar = LazyModule(new TLXbar)
  smemNodes.foreach(smemXbar.node := _)
  smemBanks.foreach(_.node := smemXbar.node)

  if (vortexParams.useVxCache) {
    tlMasterXbar.node := TLWidthWidget(16) := memNode
  } else {
    // imemNodes.foreach { tlMasterXbar.node := TLWidthWidget(4) := _ }
    tlMasterXbar.node :=* icacheNode
    tlMasterXbar.node :=* dcacheNode
  }

  /* below are copied from rocket */

  val bus_error_unit = vortexParams.beuAddr map { a =>
    val beu =
      LazyModule(new BusErrorUnit(new L1BusErrors, BusErrorUnitParams(a)))
    intOutwardNode := beu.intNode
    connectTLSlave(beu.node, xBytes)
    beu
  }

  val tile_master_blocker =
    tileParams.blockerCtrlAddr
      .map(
        BasicBusBlockerParams(_, xBytes, masterPortBeatBytes, deadlock = true)
      )
      .map(bp => LazyModule(new BasicBusBlocker(bp)))

  tile_master_blocker.foreach(lm => connectTLSlave(lm.controlNode, xBytes))

  // TODO: this doesn't block other masters, e.g. RoCCs
  tlOtherMastersNode := tile_master_blocker.map {
    _.node := tlMasterXbar.node
  } getOrElse { tlMasterXbar.node }
  masterNode :=* tlOtherMastersNode
  DisableMonitors { implicit p => tlSlaveXbar.node :*= slaveNode }

  val dtimProperty =
    Nil // Seq(dmemDevice.asProperty).flatMap(p => Map("sifive,dtim" -> p))

  val itimProperty =
    Nil // frontend.icache.itimProperty.toSeq.flatMap(p => Map("sifive,itim" -> p))

  val beuProperty = bus_error_unit
    .map(d => Map("sifive,buserror" -> d.device.asProperty))
    .getOrElse(Nil)

  val cpuDevice: SimpleDevice = new SimpleDevice(
    "cpu",
    Seq(s"sifive,vortex${tileParams.hartId}", "riscv")
  ) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(
        name,
        mapping ++ cpuProperties ++ nextLevelCacheProperty
          ++ tileProperties ++ dtimProperty ++ itimProperty ++ beuProperty
      )
    }
  }

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(staticIdForMetadataUseOnly))
  }

  override lazy val module = new VortexTileModuleImp(this)

  override def makeMasterBoundaryBuffers(
      crossing: ClockCrossingType
  )(implicit p: Parameters) = (vortexParams.boundaryBuffers, crossing) match {
    case (Some(RocketTileBoundaryBufferParams(true)), _) => TLBuffer()
    case (Some(RocketTileBoundaryBufferParams(false)), _: RationalCrossing) =>
      TLBuffer(
        BufferParams.none,
        BufferParams.flow,
        BufferParams.none,
        BufferParams.flow,
        BufferParams(1)
      )
    case _ => TLBuffer(BufferParams.none)
  }

  override def makeSlaveBoundaryBuffers(
      crossing: ClockCrossingType
  )(implicit p: Parameters) = (vortexParams.boundaryBuffers, crossing) match {
    case (Some(RocketTileBoundaryBufferParams(true)), _) => TLBuffer()
    case (Some(RocketTileBoundaryBufferParams(false)), _: RationalCrossing) =>
      TLBuffer(
        BufferParams.flow,
        BufferParams.none,
        BufferParams.none,
        BufferParams.none,
        BufferParams.none
      )
    case _ => TLBuffer(BufferParams.none)
  }
}

class VortexTileModuleImp(outer: VortexTile) extends BaseTileModuleImp(outer) {
  Annotated.params(this, outer.vortexParams)

  val core = Module(new Vortex(outer)(outer.p))

  core.io.clock := clock
  core.io.reset := reset

  // begin @copypaste from RocketTile ------------------------------------------

  // reset vector is connected in the Frontend to s2_pc
  core.io.reset_vector := DontCare

  outer.regNode.regmap(
    0x00 -> Seq(RegField.r(32, core.io.cease))
  )

  // Report when the tile has ceased to retire instructions; for now the only cause is clock gating
  outer.reportCease(outer.vortexParams.core.clockGate.option(core.io.cease))

  outer.reportWFI(Some(core.io.wfi))

  outer.decodeCoreInterrupts(core.io.interrupts) // Decode the interrupt vector

  outer.bus_error_unit.foreach { beu =>
    core.io.interrupts.buserror.get := beu.module.io.interrupt
  }

  core.io.interrupts.nmi.foreach { nmi => nmi := outer.nmiSinkNode.bundle }

  // Pass through various external constants and reports that were bundle-bridged into the tile
  // outer.traceSourceNode.bundle <> core.io.trace
  core.io.traceStall := outer.traceAuxSinkNode.bundle.stall
  // outer.bpwatchSourceNode.bundle <> core.io.bpwatch

  // not necessary for Vortex as hartId is set via Verilog parameter
  // core.io.hartid := outer.hartIdSinkNode.bundle
  // require(core.io.hartid.getWidth >= outer.hartIdSinkNode.bundle.getWidth,
  //   s"core hartid wire (${core.io.hartid.getWidth}b) truncates external hartid wire (${outer.hartIdSinkNode.bundle.getWidth}b)")

  // end @copypaste from RocketTile --------------------------------------------

  // ---------------------------------------------
  // Translate Vortex memory interface to TileLink
  // ---------------------------------------------

  if (outer.vortexParams.useVxCache) {
    println(s"width of a channel data ${core.io.mem.get.a.bits.data.getWidth}")
    println(s"width of d channel data ${core.io.mem.get.d.bits.data.getWidth}")

    val memTLAdapter = Module(
      new VortexTLAdapter(
        outer.dmemSourceWidth,
        chiselTypeOf(core.io.mem.get.a.bits),
        chiselTypeOf(core.io.mem.get.d.bits),
        outer.memNode.out.head
      )
    )

    // connection: VortexBundle <--> VortexTLAdapter <--> TL memNode
    memTLAdapter.io.inReq <> core.io.mem.get.a
    core.io.mem.get.d <> memTLAdapter.io.inResp
    outer.memNode.out(0)._1.a <> memTLAdapter.io.outReq
    memTLAdapter.io.outResp <> outer.memNode.out(0)._1.d
  } else {
    def connectImem = {
      val imemTLAdapter = Module(
        new VortexTLAdapter(
          outer.imemSourceWidth,
          chiselTypeOf(core.io.imem.get(0).a.bits),
          chiselTypeOf(core.io.imem.get(0).d.bits),
          outer.imemNodes.head.out.head
        )
      )
      // TODO: make imemNodes not a vector
      imemTLAdapter.io.inReq <> core.io.imem.get(0).a
      core.io.imem.get(0).d <> imemTLAdapter.io.inResp
      outer.imemNodes(0).out(0)._1.a <> imemTLAdapter.io.outReq
      imemTLAdapter.io.outResp <> outer.imemNodes(0).out(0)._1.d
    }

    def connectDmem = {
      // @perf: this would duplicate SourceGenerator table for every lane and eat
      // up some area
      val dmemTLBundles = outer.dmemNodes.map(_.out.head._1)
      val dmemTLAdapters = Seq.tabulate(outer.numLanes) { _ =>
        Module(
          new VortexTLAdapter(
            outer.dmemSourceWidth,
            new VortexBundleA(tagWidth = outer.dmemTagWidth, dataWidth = 32),
            new VortexBundleD(tagWidth = outer.dmemTagWidth, dataWidth = 32),
            outer.dmemNodes(0).out.head
          )
        )
      }

      // Since the individual per-lane TL requests might come back out-of-sync between
      // the lanes, but Vortex core expects the per-lane responses to be synced,
      // we need to selectively fire responses that have the same source, and
      // delay others.
      //
      // In order to do that, we pick a source from one of the valid lanes using e.g.
      // an arbiter.  Then using the chosen source id, we
      // - lie to core that response is not valid if source doesn't match picked, and
      // - lie to downstream that core is not ready if source doesn't match picked.
      //
      // Note that we cannot do this filtering logic using TileLink source ID, because
      // we're allocating source for each lane independently.  In that case, it's
      // possible that lane 0's source matches lane 1/2/3's source by chance,
      // even when they originated from different warps.  Using Vortex's dcache req tag
      // solves this issue because they use a UUID that is unique across all requests
      // in the program.
      //
      // TODO: A cleaner solution would be to simply do a synchronized allocation
      // of a same source id for all lanes.
      val arb = Module(
        new RRArbiter(
          // FIXME: should really be source on D channel
          new VortexBundleA(tagWidth = outer.dmemTagWidth, dataWidth = 32).source.cloneType,
          outer.numLanes
        )
      )
      arb.io.out.ready := true.B
      val dmemBundles = dmemTLAdapters.map(_.io.inResp)
      (arb.io.in zip dmemBundles).foreach { case (arbIn, vxDmem) =>
        arbIn.valid := vxDmem.valid
        arbIn.bits := vxDmem.bits.source
      }
      val matchingSources = Wire(UInt(outer.numLanes.W))
      matchingSources := dmemBundles
        .map(b =>
          // If there is no valid response pending across all lanes,
          // matchingSources should not filter out upstream ready signals, so
          // set it to all-1
          !arb.io.out.valid || (b.bits.source === arb.io.out.bits)
        )
        .asUInt

      // make connection:
      // VortexBundle <--> sourceId filter <--> VortexTLAdapter <--> dmemNodes
      // 
      // Chisel doesn't support 2-D array in BlackBox interface to Verilog, so
      // need to flatten everything.
      dmemTLAdapters.zipWithIndex.foreach {
        case (tlAdapter, i) =>
          // tlAdapter.io.inReq <> coreMem.a
          tlAdapter.io.inReq.valid := core.io.dmem_a_valid(i)
          tlAdapter.io.inReq.bits.opcode := core.io.dmem_a_bits_opcode(3 * (i + 1) - 1, 3 * i)
          tlAdapter.io.inReq.bits.size := core.io.dmem_a_bits_size(4 * (i + 1) - 1, 4 * i)
          tlAdapter.io.inReq.bits.source := core.io.dmem_a_bits_source(outer.dmemTagWidth * (i + 1) - 1, outer.dmemTagWidth * i)
          tlAdapter.io.inReq.bits.address := core.io.dmem_a_bits_address(32 * (i + 1) - 1, 32 * i)
          tlAdapter.io.inReq.bits.mask := core.io.dmem_a_bits_mask(4 * (i + 1) - 1, 4 * i)
          tlAdapter.io.inReq.bits.data := core.io.dmem_a_bits_data(32 * (i + 1) - 1, 32 * i)
      }
      core.io.dmem_a_ready := dmemTLAdapters.map(_.io.inReq.ready).asUInt

      core.io.dmem_d_valid := dmemTLAdapters.map(_.io.inResp.valid).asUInt
      core.io.dmem_d_bits_opcode := dmemTLAdapters.map(_.io.inResp.bits.opcode).asUInt
      core.io.dmem_d_bits_size := dmemTLAdapters.map(_.io.inResp.bits.size).asUInt
      core.io.dmem_d_bits_source := dmemTLAdapters.map(_.io.inResp.bits.source).asUInt
      core.io.dmem_d_bits_data := dmemTLAdapters.map(_.io.inResp.bits.data).asUInt

      // override response channel with matchingSources
      val dmem_d_valid_vec = Wire(Vec(outer.numLanes, Bool()))
      dmemTLAdapters.zipWithIndex.foreach {
        case (tlAdapter, i) =>
          dmem_d_valid_vec(i) := tlAdapter.io.inResp.valid && matchingSources(i)
          tlAdapter.io.inResp.ready := core.io.dmem_d_ready(i) && matchingSources(i)
      }
      core.io.dmem_d_valid := dmem_d_valid_vec.asUInt
      (dmemTLAdapters zip dmemTLBundles) foreach { case (tlAdapter, tlOut) =>
        tlOut.a <> tlAdapter.io.outReq
        tlAdapter.io.outResp <> tlOut.d
      }

      outer.dmemAggregateNode.out.foreach { bo =>
        dontTouch(bo._1.a)
        dontTouch(bo._1.d)
      }
    }

    def connectSmem = {
      // @perf: this would duplicate SourceGenerator table for every lane and eat
      // up some area
      val smemTLBundles = outer.smemNodes.map(_.out.head._1)
      val smemTLAdapters = Seq.tabulate(outer.numLanes) { _ =>
        Module(
          new VortexTLAdapter(
            outer.smemSourceWidth,
            chiselTypeOf(core.io.smem.get(0).a.bits),
            chiselTypeOf(core.io.smem.get(0).d.bits),
            outer.smemNodes(0).out.head
          )
        )
      }
      (core.io.smem.get zip smemTLAdapters) foreach {
        case (coreMem, tlAdapter) =>
          tlAdapter.io.inReq <> coreMem.a
          coreMem.d <> tlAdapter.io.inResp
      }
      (smemTLAdapters zip smemTLBundles) foreach { case (tlAdapter, tlOut) =>
        tlOut.a <> tlAdapter.io.outReq
        tlAdapter.io.outResp <> tlOut.d
      }
    }

    connectImem
    connectDmem
    connectSmem
  }

  // TODO: generalize for useVxCache
  if (!outer.vortexParams.useVxCache) {}
}

// Some @copypaste from CoalescerSourceGen.
class VortexTLAdapter(
    newSourceWidth: Int,
    inReqT: VortexBundleA,
    inRespT: VortexBundleD,
    outTL: (TLBundle, TLEdge)
) extends Module {
  val io = IO(new Bundle {
    // in/out means upstream/downstream
    val inReq = Flipped(Decoupled(inReqT))
    val outReq = chiselTypeOf(outTL._1.a)
    val inResp = Decoupled(inRespT)
    val outResp = chiselTypeOf(outTL._1.d)
  })
  val (bundle, edge) = outTL
  val sourceGen = Module(
    new SourceGenerator(
      newSourceWidth,
      Some(inReqT.source),
      ignoreInUse = false
    )
  )
  sourceGen.io.gen := io.outReq.fire // use up a source ID only when request is created
  sourceGen.io.reclaim.valid := io.outResp.fire
  sourceGen.io.reclaim.bits := io.outResp.bits.source
  sourceGen.io.meta := io.inReq.bits.source

  // io passthrough logic
  // TLBundleA <> VortexBundleA
  io.outReq.valid := io.inReq.valid
  io.outReq.bits.opcode := io.inReq.bits.opcode
  io.outReq.bits.param := 0.U
  io.outReq.bits.size := io.inReq.bits.size
  io.outReq.bits.source := io.inReq.bits.source
  io.outReq.bits.address := io.inReq.bits.address
  // Get requires contiguous mask; only copy core's potentially-partial mask
  // when writing
  io.outReq.bits.mask := Mux(
    edge.hasData(io.outReq.bits),
    io.inReq.bits.mask,
    // generate TL-correct mask
    edge.mask(io.inReq.bits.address, io.inReq.bits.size)
  )
  io.outReq.bits.data := io.inReq.bits.data
  io.outReq.bits.corrupt := 0.U
  io.inReq.ready := io.outReq.ready
  // VortexBundleD <> TLBundleD
  // Filtering out write requests is handled inside the wrapper Verilog
  io.inResp.valid := io.outResp.valid
  io.inResp.bits.opcode := io.outResp.bits.opcode
  io.inResp.bits.size := io.outResp.bits.size
  io.inResp.bits.source := io.outResp.bits.source
  io.inResp.bits.data := io.outResp.bits.data
  io.outResp.ready := io.inResp.ready

  // "man-in-the-middle"
  io.inReq.ready := io.outReq.ready && sourceGen.io.id.valid
  io.outReq.valid := io.inReq.valid && sourceGen.io.id.valid
  io.outReq.bits.source := sourceGen.io.id.bits
  // translate upstream response back to its old sourceId
  io.inResp.bits.source := sourceGen.io.peek
}
