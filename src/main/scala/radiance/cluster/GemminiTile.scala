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
import gemmini._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.diplomacy.lazymodule._
import radiance.memory.SourceGenerator
import radiance.subsystem.{GPUMemParams, GPUMemory, PhysicalCoreParams}

object GemminiCoreParams extends PhysicalCoreParams {
  override val xLen: Int = 64
}

case class GemminiScalingFactorMemConfig(
  baseAddr: BigInt,
  sizeInBytes: BigInt = 32 << 10,
  sramLineSizeInBytes: Int = 32,
  logicalLineSizeInBytes: Int = 32,
) {
  def addrBits = log2Ceil(sizeInBytes)
  def lineOffsetBits = log2Ceil(sramLineSizeInBytes)
  def bankSelectBits = log2Ceil(logicalLineSizeInBytes / sramLineSizeInBytes)
}

class ScalingFactorWriteReq(addrWidth: Int, dataWidth: Int) extends Bundle {
  val addr = UInt(addrWidth.W) // 9 bit [8:1]: row_addr, [0]:bank_sel
  val data = UInt(dataWidth.W) // 256 bits
}

case class GemminiRequantizerConfig(
  baseAddr: BigInt,
  numInputLanes: Int = 16,
  numOutputLanes: Int = 32,
  gpuMaxFactor: Int = 2, // maximum fp16->fp8 for gpus, determines address space size
  gpuWordSize: Int = 4,
  inputBits: Int = 16,
  minOutputBits: Int = 4,
  maxOutputBits: Int = 8,
  outputIdBits: Int = 3,
)

case class GemminiLUTConfig(
  numBits: Int = 96
)

object RequantizerDataType extends ChiselEnum {
  val FP4, FP6, FP8 = Value

  def widthBits(x: Type): UInt = {
    Mux(x === FP4, 4.U(4.W), 8.U(4.W))
  }
}

class RequantizerInBundle(numLanes: Int, dataWidth: Int = 16) extends Bundle {
  val data = Vec(numLanes, UInt(dataWidth.W))
  val address = UInt(32.W) // in bytes
  val dataType = RequantizerDataType()
}

class RequantizerOutBundle(numLanes: Int, dataWidth: Int = 8) extends Bundle {
  val data = UInt((numLanes * dataWidth).W) // no active byte lanes (valid from lsb)
  val address = UInt(32.W)
  val dataType = RequantizerDataType() // data type determines response size
}

case class GemminiTileParams(
  tileId: Int = 0,
  gemminiConfig: GemminiArrayConfig[Float, Float, Float],
  tileSize: Either[(Int, Int, Int), Int] = Right(4),
  mmioAddress: BigInt,
  scalingFactorMem: Option[GemminiScalingFactorMemConfig] = None,
  requantizer: Option[GemminiRequantizerConfig] = None,
  lookupTable: Option[GemminiLUTConfig] = None,
  hasAccSlave: Boolean = false,
) extends InstantiableTileParams[GemminiTile] {
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
  val uniqueName = s"${baseName}_tileId"
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
    with SourcesExternalNotifications {

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
    address = Seq(AddressSet(gemminiParams.mmioAddress, 0xff)),
    device = regDevice,
    beatBytes = 8,
    concurrency = 1)

  // regNode := TLFragmenter(4, 4) := TLWidthWidget(8) := TLFragmenter(8, 8) := slaveNode
  regNode := tlSlaveXbar.node

  val scalingFacNode = gemminiParams.scalingFactorMem.map { sfm =>
    // since gemmini slave address starts at 0x3000, +0x5000 means
    // the scaling factor memory starts 0x8000 + shared mem size,
    // which is usually 0x28000 after cluster base address. this address is
    // 32K aligned.

    require(isPow2(sfm.sizeInBytes), "scaling fac memory size must be power of 2")
    require(isPow2(sfm.sramLineSizeInBytes), "scaling fac line size must be power of 2")
    require(isPow2(sfm.logicalLineSizeInBytes), "scaling fac line size must be power of 2")

    TLManagerNode(Seq(TLSlavePortParameters.v1(
      managers = Seq(TLSlaveParameters.v2(
        address = Seq(AddressSet(sfm.baseAddr, sfm.sizeInBytes - 1)),
        fifoId = Some(0),
        supports = TLMasterToSlaveTransferSizes(
          // there's no real get support because the scaling factor memory is
          // write-only from the control bus
          get = TransferSizes(1, sfm.sramLineSizeInBytes),
          putFull = TransferSizes(1, sfm.sramLineSizeInBytes),
          putPartial = TransferSizes(1, sfm.sramLineSizeInBytes),
        )
      )),
      beatBytes = sfm.sramLineSizeInBytes,
    )))
  }
  scalingFacNode.foreach(_ := TLWidthWidget(8) := tlSlaveXbar.node)

  val requantizerMuonManager = gemminiParams.requantizer.map { q =>
    val gemminiSpadSizeBytes = gemminiParams.gemminiConfig.sp_capacity
      .asInstanceOf[CapacityInKilobytes].kilobytes * 1024
    require(isPow2(gemminiSpadSizeBytes))
    val reqSize = q.numInputLanes * q.inputBits / 8

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

  // TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1("")))))

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

  // scaling factor
  val scalingFacIO = outer.scalingFacNode.foreach { scalingFacNode =>
    val conf = outer.gemminiParams.scalingFactorMem.get
    val (node, edge) = scalingFacNode.in.head

    val wen = WireInit(node.a.fire)
    val writeData = WireInit(node.a.bits.data)

    val writeFullAddr = node.a.bits.address(conf.addrBits - 1, 0)
    val lineAddr = WireInit(writeFullAddr(conf.addrBits - 1, conf.lineOffsetBits))

    // val sramRowAddr = WireInit(lineAddr(conf.addrBits - conf.lineOffsetBits - 1, conf.bankSelectBits))
    // val bankSelect = WireInit(lineAddr(conf.bankSelectBits - 1, 0))

    val scalingFacWriteReq = Wire(Valid(new ScalingFactorWriteReq(
      conf.addrBits - conf.lineOffsetBits, conf.sramLineSizeInBytes * 8)))
    scalingFacWriteReq.valid := wen
    scalingFacWriteReq.bits.addr := lineAddr
    scalingFacWriteReq.bits.data := writeData

    node.a.ready := node.d.ready
    node.d.valid := node.a.valid
    node.d.bits := edge.AccessAck(node.a.bits)

    require(node.params.dataBits == conf.sramLineSizeInBytes * 8)
    assert(!node.a.valid || node.a.bits.opcode.isOneOf(TLMessages.PutFullData, TLMessages.PutPartialData))
    assert(!node.a.valid || (node.a.bits.size === 3.U))

    // TODO connect scaling fac mem from Gemmini
    dontTouch(scalingFacWriteReq)

    scalingFacWriteReq
  }

  // requantizer
  val requantizerIO = outer.gemminiParams.requantizer.map { q =>
    val in = Wire(Decoupled(new RequantizerInBundle(q.numInputLanes, q.inputBits)))
    val out = Wire(Decoupled(new RequantizerOutBundle(q.numOutputLanes, q.maxOutputBits)))

    { // input
      val (node, edge) = outer.requantizerMuonManager.get.in.head

      node.d.valid := node.a.valid && in.ready // ensures a.fire <=> d.fire
      node.d.bits := edge.AccessAck(node.a.bits)
      node.a.ready := node.d.ready && in.ready

      assert(node.a.fire === node.d.fire)

      in.valid := node.a.valid
      in.bits.dataType := RequantizerDataType.FP8
      in.bits.address := ((node.a.bits.address - q.baseAddr.U) >> 1).asTypeOf(in.bits.address) // hardcoded 16->8
      in.bits.data := node.a.bits.data.asTypeOf(in.bits.data)

      require(in.bits.data.asUInt.getWidth == node.a.bits.data.getWidth)
      assert(!node.a.valid || ((node.a.bits.address & q.baseAddr.U) === q.baseAddr.U))
    }

    { // output
      val (node, edge) = outer.requantizerSmemClient.get.out.head
      node.a.valid := out.valid

      // source
      val sourceGen = Module(new SourceGenerator(q.outputIdBits))
      sourceGen.io.reclaim.valid := node.d.fire
      sourceGen.io.reclaim.bits := node.d.bits.source
      sourceGen.io.gen := node.a.fire

      out.ready := node.a.ready && sourceGen.io.id.valid

      // data
      val isFP4 = out.bits.dataType === RequantizerDataType.FP4
      val fullWidth = q.numOutputLanes
      val halfWidth = q.numOutputLanes / 2
      node.a.bits := edge.Put(
        fromSource = sourceGen.io.id.bits,
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

      node.d.ready := true.B
    }

    out.valid := false.B // TODO connect requantizer module from Gemmini
    out.bits := DontCare
    in.ready := false.B

    dontTouch(in)
    dontTouch(out)
    (in, out)
  }

  // lut
  val lutIO = outer.gemminiParams.lookupTable.map(c => Wire(Decoupled(UInt(c.numBits.W))))
  lutIO.foreach(_.ready := false.B) // TODO connect lut io from Gemmini
  lutIO.foreach(dontTouch(_))

  // cisc
  val cisc = new GemminiCISC(
    accSlave = outer.accSlaveNode,
    busy = outer.gemmini.module.io.busy,
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
  val mmioLoopStarted = Mux(regValid && (regCommand.funct === GemminiISA.LOOP_WS), 1.U, 0.U)
  val runningLoops = RegInit(0.U(4.W))
  val completionCount = PopCount(outer.gemmini.module.completion_io.completed)
  runningLoops := runningLoops + loopStarted + mmioLoopStarted - completionCount
  assert(runningLoops + loopStarted + mmioLoopStarted >= completionCount)

  def gemminiRunningLoopsReg(_dReady: Bool): (Bool, UInt) = {
    (true.B, runningLoops)
  }

  val gemminiBaseMMIO = Seq(
    0x00 -> Seq(RegField.w(32, gemminiCommandReg(_, _))),
    0x10 -> Seq(
      RegField.w(32, gemminiRs1RegLSB),
      RegField.w(32, gemminiRs1RegMSB)),
    0x18 -> Seq(
      RegField.w(32, gemminiRs2RegLSB),
      RegField.w(32, gemminiRs2RegMSB)),
    0x20 -> Seq(RegField.r(32, gemminiBusyReg(_))),
    0x28 -> Seq(RegField.r(32, gemminiRunningLoopsReg(_)))
  )

  val gemminiCiscMMIO = Option.when(!outer.gemminiParams.hasAccSlave) {
    def gemminiCisc(valid: Bool, bits: UInt): Bool = {
      cisc.accCommandQueue.io.enq.bits := bits
      cisc.accCommandQueue.io.enq.valid := valid
      true.B
    }
    0x30 -> Seq(RegField.w(32, gemminiCisc(_, _)))
  }.toSeq

  val gemminiLutMMIO = lutIO.map { io =>
    require(io.bits.getWidth == 96)

    val lutW0 = RegInit(0.U(32.W))
    val lutW1 = RegInit(0.U(32.W))
    def lutW2(valid: Bool, bits: UInt): Bool = {
      io.valid := valid
      io.bits := Cat(bits, lutW1, lutW0)
      io.ready
    }
    Seq(
      0x40 -> Seq(RegField.w(32, lutW0)),
      0x44 -> Seq(RegField.w(32, lutW1)),
      0x48 -> Seq(RegField.w(32, lutW2(_, _))),
    )
  }.toSeq.flatten

  outer.regNode.regmap(
    (gemminiBaseMMIO ++ gemminiCiscMMIO ++ gemminiLutMMIO):_*
  )

  assert(!regValid || gemminiIO.ready)
  assert(!cisc.ciscValid || gemminiIO.ready)

  gemminiIO.bits.status := 0.U.asTypeOf(gemminiIO.bits.status)
  gemminiIO.bits.inst := Mux(cisc.ciscValid,
    cisc.ciscInst.inst.asTypeOf(gemminiIO.bits.inst), regCommand)
  gemminiIO.bits.rs1 := Mux(cisc.ciscValid,
    cisc.ciscInst.rs1, Cat(gemminiRs1RegMSB, gemminiRs1RegLSB))
  gemminiIO.bits.rs2 := Mux(cisc.ciscValid,
    cisc.ciscInst.rs2, Cat(gemminiRs2RegMSB, gemminiRs2RegLSB))
  gemminiIO.valid := (cisc.ciscValid && (cisc.ciscInst.inst =/= 0.U)) || regValid
  assert(gemminiIO.ready || !gemminiIO.valid)

  outer.traceSourceNode.bundle := DontCare
  outer.traceSourceNode.bundle.insns foreach (_.valid := false.B)

  // hacky, but cluster will AND the cease signals from all tiles, and we want
  // the core tiles to determine cluster cease not Gemmini
  outer.reportCease(Some(true.B))
}
