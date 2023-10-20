// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package tile

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
import rocket.{Vortex, VortexBundleA, VortexBundleD}

case class RocketTileBoundaryBufferParams(force: Boolean = false)

case class VortexTileParams(
    core: RocketCoreParams = RocketCoreParams(),
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

  val regDevice = new SimpleDevice("vortex-reg", Seq(s"vortex-reg${tileParams.hartId}"))
  val regNode = TLRegisterNode(
    address = Seq(AddressSet(0x7c000000 + 0x1000 * tileParams.hartId, 0xfff)),
    device = regDevice,
    beatBytes = 4,
    concurrency = 1)

  regNode := tlSlaveXbar.node

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

  val numLanes = 4 // TODO: use Parameters for this
  val sourceWidth = 4 // TODO: use Parameters for this

  val imemNodes = Seq.tabulate(1) { i =>
    TLClientNode(
      Seq(
        TLMasterPortParameters.v1(
          clients = Seq(
            TLMasterParameters.v1(
              sourceId = IdRange(0, 1 << sourceWidth),
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
              sourceId = IdRange(0, 1 << sourceWidth),
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

  println(s"============= lazyCoreParamsView.coreDataBytes=${lazyCoreParamsView.coreDataBytes}")
  val memNode = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        clients = Seq(
          TLMasterParameters.v1(
            sourceId = IdRange(0, 1 << sourceWidth),
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

  if (vortexParams.useVxCache) {
    tlMasterXbar.node := TLWidthWidget(16) := memNode
  } else {
    imemNodes.foreach { tlMasterXbar.node := _ }
    dmemNodes.foreach { tlMasterXbar.node := _ }
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

  val cpuDevice: SimpleDevice =
    new SimpleDevice("cpu", Seq("sifive,vortex0", "riscv")) {
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

  // Copypasted from Rocket; not necessary for Vortex as hartId is set via Verilog parameter
  // core.io.hartid := outer.hartIdSinkNode.bundle
  // require(core.io.hartid.getWidth >= outer.hartIdSinkNode.bundle.getWidth,
  //   s"core hartid wire (${core.io.hartid.getWidth}b) truncates external hartid wire (${outer.hartIdSinkNode.bundle.getWidth}b)")

  // ---------------------------------------------
  // Translate Vortex memory interface to TileLink
  // ---------------------------------------------

  if (outer.vortexParams.useVxCache) {
    println(s"width of a channel data ${core.io.mem.get.a.bits.data.getWidth}")
    println(s"width of d channel data ${core.io.mem.get.d.bits.data.getWidth}")

    val memTLAdapter =  Module(new VortexTLAdapter(
      outer.sourceWidth,
      chiselTypeOf(core.io.mem.get.a.bits),
      chiselTypeOf(core.io.mem.get.d.bits),
      chiselTypeOf(outer.memNode.out.head._1.a.bits),
      chiselTypeOf(outer.memNode.out.head._1.d.bits),
    ))

    // connection: VortexBundle <--> VortexTLAdapter <--> TL memNode
    memTLAdapter.io.inReq <> core.io.mem.get.a
    core.io.mem.get.d <> memTLAdapter.io.inResp
    outer.memNode.out(0)._1.a <> memTLAdapter.io.outReq
    memTLAdapter.io.outResp <> outer.memNode.out(0)._1.d

    // core.io.mem.get.a <> outer.memNode.out.head._1.a
    // core.io.mem.get.d <> outer.memNode.out.head._1.d
  } else {
    val imemTLAdapter =  Module(new VortexTLAdapter(
        outer.sourceWidth,
        chiselTypeOf(core.io.imem.get(0).a.bits),
        chiselTypeOf(core.io.imem.get(0).d.bits),
        chiselTypeOf(outer.imemNodes.head.out.head._1.a.bits),
        chiselTypeOf(outer.imemNodes.head.out.head._1.d.bits),
    ))
    // TODO: make imemNodes not a vector
    imemTLAdapter.io.inReq <> core.io.imem.get(0).a
    core.io.imem.get(0).d <> imemTLAdapter.io.inResp
    outer.imemNodes(0).out(0)._1.a <> imemTLAdapter.io.outReq
    imemTLAdapter.io.outResp <> outer.imemNodes(0).out(0)._1.d

    // Since the individual per-lane TL requests might come back out-of-sync between
    // the lanes, but Vortex core expects the lane requests to be synced,
    // we need to selectively fire responses that have the same source, and
    // delay others.  Below is the logic that implements this.

    // choose one source out of the arriving per-lane TL D channels
    val arb = Module(
      new RRArbiter(core.io.dmem.get.head.d.bits.source.cloneType, outer.numLanes)
    )
    val dmemTLBundles = outer.dmemNodes.map(_.out.head._1)
    arb.io.out.ready := true.B
    (arb.io.in zip dmemTLBundles).foreach { case (arbIn, tlBundle) =>
      arbIn.valid := tlBundle.d.valid
      arbIn.bits := tlBundle.d.bits.source
    }
    val matchingSources = Wire(UInt(outer.numLanes.W))
    matchingSources := dmemTLBundles
      .map(b => (b.d.bits.source === arb.io.out.bits) && arb.io.out.valid)
      .asUInt

    // connection: VortexBundle <--> VortexTLAdapter <--> dmemNodes
    // @perf: this would duplicate SourceGenerator table for every lane and eat
    // up some area
    val dmemTLAdapters = Seq.tabulate(outer.numLanes) { _ =>
      Module(new VortexTLAdapter(
        outer.sourceWidth,
        chiselTypeOf(core.io.dmem.get(0).a.bits),
        chiselTypeOf(core.io.dmem.get(0).d.bits),
        chiselTypeOf(dmemTLBundles.head.a.bits),
        chiselTypeOf(dmemTLBundles.head.d.bits),
      ))
    }
    (core.io.dmem.get zip dmemTLAdapters) foreach { case (coreMem, tlAdapter) =>
      tlAdapter.io.inReq <> coreMem.a
      coreMem.d <> tlAdapter.io.inResp
    }
    (dmemTLAdapters zip dmemTLBundles) foreach { case (tlAdapter, tlBundle) =>
      tlBundle.a <> tlAdapter.io.outReq
    }
    // using the chosen source id,
    // - lie to core that response is not valid if source doesn't match picked
    // - lie to downstream that core is not ready if source doesn't match picked
    (dmemTLAdapters zip dmemTLBundles).zipWithIndex.foreach {
      case ((tlAdapter, tlBundle), i) =>
        tlAdapter.io.outResp.bits := tlBundle.d.bits
        tlAdapter.io.outResp.valid := tlBundle.d.valid && matchingSources(i)
        tlBundle.d.ready := tlAdapter.io.outResp.ready && matchingSources(i)
    }

    // (core.io.dmem.get zip outer.dmemNodes).foreach { case (coreMem, tileNode) =>
    //   tileNode.out.head._1.a <> coreMem.a
    // }
  }

  // core.io.fpu := DontCare

  // TODO eliminate this redundancy
  // val h = dcachePorts.size
  // val c = core.dcacheArbPorts
  // val o = outer.nDCachePorts
  // require(h == c, s"port list size was $h, core expected $c")
  // require(h == o, s"port list size was $h, outer counted $o")
  // TODO figure out how to move the below into their respective mix-ins
  // dcacheArb.io.requestor <> dcachePorts.toSeq
}

// Some @copypaste from CoalescerSourceGen.
class VortexTLAdapter(
  newSourceWidth: Int,
  inReqT: VortexBundleA,
  inRespT: VortexBundleD,
  outReqT: TLBundleA,
  outRespT: TLBundleD
) extends Module {
  val io = IO(new Bundle {
    // in/out means upstream/downstream
    val inReq = Flipped(Decoupled(inReqT))
    val outReq = Decoupled(outReqT)
    val inResp = Decoupled(inRespT)
    val outResp = Flipped(Decoupled(outRespT))
  })
  val sourceGen = Module(new SourceGenerator(
    newSourceWidth,
    Some(inReqT.source),
    ignoreInUse = false
  ))
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
  io.outReq.bits.mask := io.inReq.bits.mask
  io.outReq.bits.data := io.inReq.bits.data
  io.outReq.bits.corrupt := 0.U
  io.inReq.ready := io.outReq.ready
  // VortexBundleD <> TLBundleD
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

// FIXME: unsure this is necessary
trait HasFpuOpt { this: RocketTileModuleImp =>
  val fpuOpt =
    outer.tileParams.core.fpu.map(params => Module(new FPU(params)(outer.p)))
}
