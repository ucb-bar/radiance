// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.cluster

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, TransferSizes}
import freechips.rocketchip.prci.{ClockCrossingType, ClockSinkParameters}
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.resources._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.{CanAttachTile, HierarchicalElementCrossingParamsLike, RocketCrossingParams}
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.UIntIsOneOf
import gemmini.Arithmetic._
import gemmini._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.diplomacy.lazymodule._
import radiance.memory._
import radiance.subsystem.{GPUMemParams, GPUMemory, GemminiTileLike, PhysicalCoreParams}

object GemminiCoreParams extends PhysicalCoreParams {
  override val xLen: Int = 64
}

final case class MxGemminiTileParams(
  tileId: Int = 0,
  mxGemminiConfig: GemminiArrayConfig[MxFloat, Float, Float],
  tileSize: Either[(Int, Int, Int), Int] = Right(4),
  mmioAddress: BigInt,
  override val scalingFactorMem: Option[GemminiScalingFactorMemConfig],
  override val requantizer: Option[GemminiRequantizerConfig],
  override val lookupTable: Option[GemminiLUTConfig],
) extends GemminiTileParams {
  type T = MxFloat
  type U = Float
  type V = Float
  override val gemminiConfig = mxGemminiConfig.copy(
    scale_mem = scalingFactorMem,
    requantizer = requantizer,
    lut = lookupTable
  )
  override val arithmetic = MxFloatArithmetic
}

final case class FloatGemminiTileParams(
  tileId: Int = 0,
  gemminiConfig: GemminiArrayConfig[Float, Float, Float],
  tileSize: Either[(Int, Int, Int), Int] = Right(4),
  mmioAddress: BigInt,
) extends GemminiTileParams {
  type T = Float
  type U = Float
  type V = Float
  override val arithmetic = FloatArithmetic
}

abstract class GemminiTileParams extends InstantiableTileParams[GemminiTile] {
  type T <: Data
  type U <: Data
  type V <: Data
  implicit val arithmetic: Arithmetic[T]
  val gemminiConfig: GemminiArrayConfig[T, U, V]
  val tileSize: Either[(Int, Int, Int), Int]
  val mmioAddress: BigInt
  val scalingFactorMem: Option[GemminiScalingFactorMemConfig] = None
  val requantizer: Option[GemminiRequantizerConfig] = None
  val lookupTable: Option[GemminiLUTConfig] = None
  val hasAccSlave: Boolean = false

  def instantiate(crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)(
      implicit p: Parameters
  ): GemminiTile = {
    new GemminiTile(this, crossing, lookup)
  }
  val core = GemminiCoreParams
  val name = Some(f"radiance_gemmini_tile_$tileId")
  val clockSinkParams = ClockSinkParameters()
  val blockerCtrlAddr = None
  val icache = None
  val dcache = Some(DCacheParams(
    nSets = 1, nWays = 1, nMSHRs = 0,
    nTLBSets = 0, nTLBWays = 1
  ))
  val btb = None
  val baseName = name.get
  val uniqueName = s"${baseName}_$tileId"
}

case class GemminiTileAttachParams(
  tileParams: GemminiTileParams,
  crossingParams: RocketCrossingParams,
) extends CanAttachTile { type TileType = GemminiTile }

class GemminiTile private (
    val gemminiParams: GemminiTileParams,
    crossing: ClockCrossingType,
    lookup: LookupByHartIdImpl,
    q: Parameters
) extends BaseTile(gemminiParams, crossing, lookup, q)
    with SinksExternalInterrupts
    with SourcesExternalNotifications
    with GemminiTileLike {

  def this(params: GemminiTileParams, crossing: HierarchicalElementCrossingParamsLike,
      lookup: LookupByHartIdImpl)(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  val cpuDevice: SimpleDevice = new SimpleDevice(s"gemmini${tileId}", Nil) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(
        name,
        mapping ++ cpuProperties ++ nextLevelCacheProperty ++ tileProperties
      )
    }
  }


  val intOutwardNode = None
  val slaveNode = TLIdentityNode()
  val masterNode = visibilityNode
  // val statusNode = BundleBridgeSource(() => new GroundTestStatus)

  val accSlaveNode = Option.when(gemminiParams.hasAccSlave)(AccNode.Slave())

  tlOtherMastersNode := tlMasterXbar.node
  masterNode :=* tlOtherMastersNode
  DisableMonitors { implicit p => tlSlaveXbar.node :*= slaveNode }

  // TODO: evaluate if gemmini write node is required at all

  implicit val arithmetic = gemminiParams.arithmetic
  val gemmini = LazyModule(new Gemmini(gemminiParams.gemminiConfig))
  val base = p(GPUMemory) match {
    case Some(GPUMemParams(baseAddr, _)) => baseAddr
    case _ => BigInt(0)
  }
  // tlMasterXbar.node :=* AddressOrNode(base) :=* gemmini.atlNode
  // tlOtherMastersNode :=* AddressOrNode(base) :=* gemmini.tlNode
  tlMasterXbar.node :=* gemmini.atlNode
  tlOtherMastersNode :=* gemmini.tlNode
  // gemmini.stlNode := tlSlaveXbar.node

  override def connectTLSlave(node: TLNode, bytes: Int): Unit = {}
  override def connectTLSlave(xbarNode: TLOutwardNode, node: TLNode, bytes: Int): Unit = {}

  require(!gemmini.config.sp_singleported, "external scratchpad must be dual ported")

  val regDevice = new SimpleDevice(f"gemmini-cmd-reg-$tileId", Seq(s"gemmini-cmd-reg-$tileId"))
  val regNode = TLRegisterNode(
    address = Seq(AddressSet(gemminiParams.mmioAddress, 0xfff)),
    device = regDevice,
    beatBytes = 8,
    concurrency = 1)

  // regNode := TLFragmenter(4, 4) := TLWidthWidget(8) := TLFragmenter(8, 8) := slaveNode
  regNode := tlSlaveXbar.node

  // Scaling factor memory and LUT contents are loaded by gemmini's own MX_LOAD_SCALES /
  // MX_LOAD_LUT DMA loaders through gemmini.tlNode; there is no MMIO write window for them.

  val scalingFacClient = gemminiParams.scalingFactorMem.map { sfm =>
    TLClientNode(Seq(TLMasterPortParameters.v1(
      clients = Seq(TLMasterParameters.v2(
        name = "gemmini-scaling-fac-client",
        sourceId = IdRange(0, 1 << 4), // TODO: magic number
        emits = TLMasterToSlaveTransferSizes(
          putFull = TransferSizes(sfm.bankWidthBytes, sfm.bankWidthBytes)
        )
      ))
    )))
  }

  val requantizerMuonManager = gemminiParams.requantizer.map { q =>
    val gemminiSpadSizeBytes = gemminiParams.gemminiConfig.sp_capacity
      .asInstanceOf[CapacityInKilobytes].kilobytes * 1024
    require(isPow2(gemminiSpadSizeBytes))
    val reqSize = q.numGPUInputLanes * q.inputBits / 8

    println("quant manager address set")
    println(AddressSet(q.baseAddr, gemminiSpadSizeBytes * q.gpuMaxFactor - 1))
    TLManagerNode(Seq(TLSlavePortParameters.v1(
      managers = Seq(TLSlaveParameters.v2(
        address = Seq(AddressSet(q.baseAddr, gemminiSpadSizeBytes * q.gpuMaxFactor - 1)),
        fifoId = Some(0),
        // also real no get support
        supports = TLMasterToSlaveTransferSizes(
          get = TransferSizes(reqSize, reqSize),
          putFull = TransferSizes(reqSize, reqSize),
          putPartial = TransferSizes(reqSize, reqSize),
        )
      )),
      beatBytes = reqSize,
    )))
  }

  // width is max output width & num output lanes
  val requantizerSmemClient = gemminiParams.requantizer.map { q =>
    TLClientNode(Seq(TLMasterPortParameters.v1(
      clients = Seq(TLMasterParameters.v2(
        name = "requantizer_out",
        sourceId = IdRange(0, 1 << q.outputIdBits),
        emits = TLMasterToSlaveTransferSizes(
          putFull = TransferSizes(q.numOutputLanes * q.minOutputBits / 8,
            q.numOutputLanes * q.maxOutputBits / 8),
          putPartial = TransferSizes(q.numOutputLanes * q.minOutputBits / 8,
            q.numOutputLanes * q.maxOutputBits / 8)
        )
      ))
    )))
  }

  override lazy val module = new GemminiTileModuleImp(this)
}

class GemminiTileModuleImp(outer: GemminiTile) extends BaseTileModuleImp(outer) {

  { // tie off rocc
    val gemmini_io = outer.gemmini.module.io
    gemmini_io.ptw <> DontCare
    gemmini_io.mem <> DontCare
    gemmini_io.resp <> DontCare
    gemmini_io.fpu_req.ready := false.B
    gemmini_io.fpu_resp.valid := false.B
    gemmini_io.fpu_resp.bits := DontCare
    gemmini_io.exception := DontCare
  }

  outer.scalingFacClient.foreach { scalingFacNode =>
    val (node, edge) = scalingFacNode.out.head
    val out = outer.gemmini.module.mx_io.get.scale_factor_out

    node.a.bits := edge.Put(
      fromSource = 0.U, // overridden
      toAddress = out.bits.addr,
      lgSize = log2Ceil(node.params.dataBits / 8).U,
      data = out.bits.data,
    )._2

    val (sourceReady, _) = SourceGenerator(node)
    out.ready := node.a.ready && sourceReady
    node.a.valid := out.valid && sourceReady
    node.d.ready := true.B
  }

  // requantizer
  outer.gemminiParams.requantizer.foreach { q =>
    val in = Wire(Decoupled(new RequantizerInBundle(q.numGPUInputLanes, q.inputBits)))
    val out = Wire(Decoupled(new RequantizerOutBundle(q.numOutputLanes, q.maxOutputBits)))

    { // input
      val (node, edge) = outer.requantizerMuonManager.get.in.head

      node.d.valid := node.a.valid && in.ready // ensures a.fire <=> d.fire
      node.d.bits := edge.AccessAck(node.a.bits)
      node.a.ready := node.d.ready && in.ready

      assert(node.a.fire === node.d.fire)

      // The requantizer must consume a beat only in the cycle its A fires.  With `in.valid :=
      // node.a.valid` alone, a cycle with in.ready && !node.d.ready (the upstream collector still
      // acknowledging its lanes) let the requantizer take the beat while the A stayed pending, so the
      // same beat entered twice: the requantizer's half-beat toggle slipped and the next block went
      // out with the previous beat's address (misaligned 32 B Put into SMEM, TLMonitor assert).
      in.valid := node.a.valid && node.d.ready
      in.bits.dataType := RequantizerDataType.FP8
      in.bits.address := ((node.a.bits.address - q.baseAddr.U) >> 1).asTypeOf(in.bits.address) // hardcoded 16->8
      in.bits.data := node.a.bits.data.asTypeOf(in.bits.data)

      require(in.bits.data.asUInt.getWidth == node.a.bits.data.getWidth)
      assert(!node.a.valid || ((node.a.bits.address & q.baseAddr.U) === q.baseAddr.U))
    }

    { // output
      val (node, edge) = outer.requantizerSmemClient.get.out.head

      // data
      val isFP4 = out.bits.dataType === RequantizerDataType.FP4
      val fullWidth = q.numOutputLanes
      val halfWidth = q.numOutputLanes / 2
      node.a.bits := edge.Put(
        fromSource = 0.U, // gets overridden
        toAddress = out.bits.address,
        lgSize = Mux(isFP4,
          log2Ceil(halfWidth).U, // half byte per lane
          log2Ceil(q.numOutputLanes).U // fp6, fp8: 1 byte per lane
        ),
        data = Mux(isFP4,
          Mux(
            out.bits.address(log2Ceil(halfWidth)), // not aligned to full line
            (out.bits.data(halfWidth - 1, 0) << halfWidth).asTypeOf(UInt(fullWidth.W)),
            out.bits.data
          ),
          out.bits.data
        )
      )._2

      // source
      val (sourceReady, _) = SourceGenerator(node)
      out.ready := node.a.ready && sourceReady
      node.a.valid := out.valid && sourceReady
      assert(out.fire === node.a.fire)
      node.d.ready := true.B
    }

    outer.gemmini.module.mx_io.get.requant_in_gpu <> in
    outer.gemmini.module.mx_io.get.requant_out <> out
  }

  // cisc
  val cisc = new GemminiCISC(
    accSlave = outer.accSlaveNode,
    busy = outer.gemmini.module.io.busy,
    spadRows = outer.gemminiParams.gemminiConfig.sp_bank_entries *
      outer.gemminiParams.gemminiConfig.sp_banks,
    tileParams = outer.gemminiParams)

  // mmio
  val gemminiIO = outer.gemmini.module.io.cmd

  val regValid = Wire(Bool())
  val regCommand = Wire(gemminiIO.bits.inst.cloneType)
  val gemminiRs1RegLSB = RegInit(0.U(32.W))
  val gemminiRs1RegMSB = RegInit(0.U(32.W))
  val gemminiRs2RegLSB = RegInit(0.U(32.W))
  val gemminiRs2RegMSB = RegInit(0.U(32.W))

  def gemminiCommandReg(valid: Bool, bits: UInt): Bool = {
    regValid := valid
    regCommand := bits.asTypeOf(regCommand)
    gemminiIO.ready && !cisc.ciscValid
  }

  def gemminiBusyReg(_dReady: Bool): (Bool, UInt) = {
    // (aReady, bits)
    // (!outer.gemmini.module.io.busy, outer.gemmini.module.io.busy.asUInt)
    (true.B, outer.gemmini.module.io.busy.asUInt)
  }

  val loopStarted = Mux(cisc.startsLoop, 1.U, 0.U)
  // Count a LOOP_WS only when its INST write is accepted.  The regmap holds a stalled write valid
  // for every cycle the command queue is full, so counting `regValid` alone over-counts, and the
  // 4-bit counter then wrapped and tripped the assert below when a kernel ran ahead of gemmini.
  val mmioLoopStarted = Mux(regValid && gemminiIO.ready && !cisc.ciscValid &&
    (regCommand.funct === GemminiISA.LOOP_WS), 1.U, 0.U)
  val runningLoops = RegInit(0.U(8.W))
  val completionCount = PopCount(outer.gemmini.module.completion_io.completed)
  runningLoops := runningLoops + loopStarted + mmioLoopStarted - completionCount
  assert(runningLoops + loopStarted + mmioLoopStarted >= completionCount)

  def gemminiRunningLoopsReg(_dReady: Bool): (Bool, UInt) = {
    (true.B, runningLoops)
  }

  val gemminiBaseMMIO = Seq(
    0x00 -> Seq(RegField.w(32, gemminiCommandReg(_, _))),
    0x08 -> Seq(RegField.r(32, gemminiIO.ready)),
    0x10 -> Seq(
      RegField.w(32, gemminiRs1RegLSB),
      RegField.w(32, gemminiRs1RegMSB)),
    0x18 -> Seq(
      RegField.w(32, gemminiRs2RegLSB),
      RegField.w(32, gemminiRs2RegMSB)),
    0x20 -> Seq(RegField.r(32, gemminiBusyReg(_))),
    0x28 -> Seq(RegField.r(32, gemminiRunningLoopsReg(_))),
  )

  val gemminiCiscMMIO = Option.when(!outer.gemminiParams.hasAccSlave) {
    def gemminiCisc(valid: Bool, bits: UInt): Bool = {
      cisc.accCommandQueue.io.enq.bits := bits
      cisc.accCommandQueue.io.enq.valid := valid
      true.B
    }
    0x30 -> Seq(RegField.w(32, gemminiCisc(_, _)))
  }.toSeq

  // has_loop_retire_counter: number of LOOP_WS fully retired (computes done, stores landed), in issue order; a kernel
  // polls it instead of BUSY to wait for one loop's results without draining the loops queued behind it
  val gemminiRetireMMIO = outer.gemmini.module.retire_io.map { r =>
    0x38 -> Seq(RegField.r(32, r.loops_retired))
  }.toSeq

  outer.regNode.regmap(
    (gemminiBaseMMIO ++ gemminiCiscMMIO ++ gemminiRetireMMIO):_*
  )

  // assert(!regValid || gemminiIO.ready)
  // assert(!cisc.ciscValid || gemminiIO.ready)

  gemminiIO.bits.status := 0.U.asTypeOf(gemminiIO.bits.status)
  gemminiIO.bits.inst := Mux(cisc.ciscValid,
    cisc.ciscInst.inst.asTypeOf(gemminiIO.bits.inst), regCommand)
  gemminiIO.bits.rs1 := Mux(cisc.ciscValid,
    cisc.ciscInst.rs1, Cat(gemminiRs1RegMSB, gemminiRs1RegLSB))
  gemminiIO.bits.rs2 := Mux(cisc.ciscValid,
    cisc.ciscInst.rs2, Cat(gemminiRs2RegMSB, gemminiRs2RegLSB))
  gemminiIO.valid := (cisc.ciscValid && (cisc.ciscInst.inst =/= 0.U)) || regValid
  // assert(gemminiIO.ready || !gemminiIO.valid)

  outer.traceSourceNode.bundle := DontCare
  outer.traceSourceNode.bundle.insns foreach (_.valid := false.B)

  // hacky, but cluster will AND the cease signals from all tiles, and we want
  // the core tiles to determine cluster cease not Gemmini
  outer.reportCease(Some(true.B))
}
