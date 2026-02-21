package radiance.unittest

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._

object Cyclotron {
  def splitUInt(flattened: UInt, wordBits: Int): Vec[UInt] = {
    assert(flattened.getWidth % wordBits == 0)
    val numWords = flattened.getWidth / wordBits
    VecInit.tabulate(numWords)(i => flattened((i + 1) * wordBits - 1, i * wordBits))
  }

  def unflatten(destWord: UInt, flattened: UInt, index: Int): Unit = {
    assert(destWord.widthKnown, "cannot unflatten to sink with unknown width")
    destWord := splitUInt(flattened, destWord.getWidth)(index)
  }
}

/** Wraps Verilog shim to convert messy flattened 1-D arrays into
 *  Chisel-friendly IOs. */
class CyclotronFrontend(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val imem = Flipped(new InstMemIO)
    val ibuf = Vec(muonParams.numWarps, Decoupled(new UOpFlattened))
    val finished = Output(Bool())
  })

  val cfbox = Module(new CyclotronFrontendBlackBox()(p))
  cfbox.io.clock := clock
  cfbox.io.reset := reset.asBool

  import Cyclotron._

  // IMem connection
  cfbox.io.imem.req <> io.imem.req
  io.imem.resp <> cfbox.io.imem.resp

  // InstBuf connection
  //
  // @cleanup: prob better to make an accessor method that extracts a single
  // IBufEntry from all the flattened arrays with a given index
  cfbox.io.ibuf.ready := VecInit(io.ibuf.map(_.ready)).asUInt
  io.ibuf.zipWithIndex.foreach { case (ib, i) =>
    ib.valid := cfbox.io.ibuf.valid(i)
    unflatten(ib.bits.pc, cfbox.io.ibuf.pc, i)
    unflatten(ib.bits.wid, cfbox.io.ibuf.wid, i)
    unflatten(ib.bits.op, cfbox.io.ibuf.op, i)
    unflatten(ib.bits.rd, cfbox.io.ibuf.rd, i)
    unflatten(ib.bits.rs1, cfbox.io.ibuf.rs1, i)
    unflatten(ib.bits.rs2, cfbox.io.ibuf.rs2, i)
    unflatten(ib.bits.rs3, cfbox.io.ibuf.rs3, i)
    unflatten(ib.bits.imm32, cfbox.io.ibuf.imm32, i)
    unflatten(ib.bits.imm24, cfbox.io.ibuf.imm24, i)
    unflatten(ib.bits.csrImm, cfbox.io.ibuf.csrImm, i)
    unflatten(ib.bits.f3, cfbox.io.ibuf.f3, i)
    unflatten(ib.bits.f7, cfbox.io.ibuf.f7, i)
    unflatten(ib.bits.pred, cfbox.io.ibuf.pred, i)
    unflatten(ib.bits.tmask, cfbox.io.ibuf.tmask, i)
    unflatten(ib.bits.raw, cfbox.io.ibuf.raw, i)
  }

  io.finished := cfbox.io.finished
}

abstract class CyclotronBlackBox(implicit val p: Parameters) extends BlackBox(Map(
      "ARCH_LEN"     -> p(MuonKey).archLen,
      "INST_BITS"    -> p(MuonKey).instBits,
      "NUM_WARPS"    -> p(MuonKey).numWarps,
      "NUM_LANES"    -> p(MuonKey).numLanes,
      "OP_BITS"      -> Isa.opcodeBits,
      "REG_BITS"     -> Isa.regBits,
      "IMM_BITS"     -> 32,
      "CSR_IMM_BITS" -> Isa.csrImmBits,
      "PRED_BITS"    -> Isa.predBits,
))

class CyclotronFrontendBlackBox(implicit p: Parameters) extends CyclotronBlackBox
with HasBlackBoxResource with HasCoreParameters {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val imem = Flipped(new InstMemIO)
    // flattened for all numWarps
    val ibuf = new Bundle with HasUOpFields {
      val ready = Input(UInt(numWarps.W))
      val valid = Output(UInt(numWarps.W))
      val pc = Output(UInt((numWarps * addressBits).W))
      val wid = Output(UInt((numWarps * log2Ceil(muonParams.numWarps)).W))
      val op = Output(UInt((numWarps * Isa.opcodeBits).W))
      val rd = Output(UInt((numWarps * Isa.regBits).W))
      val rs1 = Output(UInt((numWarps * Isa.regBits).W))
      val rs2 = Output(UInt((numWarps * Isa.regBits).W))
      val rs3 = Output(UInt((numWarps * Isa.regBits).W))
      val imm32 = Output(UInt((numWarps * 32).W))
      val imm24 = Output(UInt((numWarps * 24).W))
      val csrImm = Output(UInt((numWarps * Isa.csrImmBits).W))
      val f3 = Output(UInt((numWarps * 3).W))
      val f7 = Output(UInt((numWarps * 7).W))
      val pred = Output(UInt((numWarps * Isa.predBits).W))
      val tmask = Output(UInt((numWarps * muonParams.numLanes).W))
      val raw = Output(UInt((numWarps * muonParams.instBits).W))
    }
    val finished = Output(Bool())
  })

  addResource("/vsrc/CyclotronFrontend.v")
  addResource("/vsrc/Cyclotron.vh")
  addResource("/csrc/Cyclotron.cc")
}

class CyclotronBackendBlackBox(implicit p: Parameters) extends CyclotronBlackBox
with HasBlackBoxResource with HasCoreParameters {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val imem = Flipped(new InstMemIO)
    val issue = Flipped(Vec(muonParams.numWarps, Decoupled(new UOpFlattened)))
    val commit = schedWritebackT

    val finished = Output(Bool())
  })

  addResource("/vsrc/CyclotronBackend.v")
  addResource("/vsrc/Cyclotron.vh")
  addResource("/csrc/Cyclotron.cc")
}

class CyclotronTile(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val imem = new InstMemIO
    val dmem = new DataMemIO
    val finished = Output(Bool())
  })

  val bbox = Module(new CyclotronTileBlackBox(this))
  bbox.io.clock := clock
  bbox.io.reset := reset.asBool

  import Cyclotron._

  // imem
  bbox.io.imem_req_ready := io.imem.req.ready
  io.imem.req.valid := bbox.io.imem_req_valid
  io.imem.req.bits.address := bbox.io.imem_req_bits_address
  io.imem.req.bits.tag := bbox.io.imem_req_bits_tag
  io.imem.req.bits.size := log2Ceil(muonParams.instBits / 8).U
  io.imem.req.bits.store := false.B
  io.imem.req.bits.data := 0.U
  io.imem.req.bits.mask := ((1 << muonParams.instBytes) - 1).U
  io.imem.resp.ready := bbox.io.imem_resp_ready
  bbox.io.imem_resp_valid := io.imem.resp.valid
  bbox.io.imem_resp_bits_tag := io.imem.resp.bits.tag
  bbox.io.imem_resp_bits_data := io.imem.resp.bits.data

  // dmem
  io.dmem.req.zipWithIndex.foreach { case (req, i) =>
    req.valid := bbox.io.dmem_req_valid(i)
    req.bits.store := bbox.io.dmem_req_bits_store(i)
    unflatten(req.bits.tag, bbox.io.dmem_req_bits_tag, i)
    unflatten(req.bits.address, bbox.io.dmem_req_bits_address, i)
    unflatten(req.bits.size, bbox.io.dmem_req_bits_size, i)
    unflatten(req.bits.data, bbox.io.dmem_req_bits_data, i)
    unflatten(req.bits.mask, bbox.io.dmem_req_bits_mask, i)
  }
  bbox.io.dmem_req_ready := VecInit(io.dmem.req.map(_.ready)).asUInt

  bbox.io.dmem_resp_valid := VecInit(io.dmem.resp.map(_.valid)).asUInt
  bbox.io.dmem_resp_bits_tag := VecInit(io.dmem.resp.map(_.bits.tag)).asUInt
  bbox.io.dmem_resp_bits_data := VecInit(io.dmem.resp.map(_.bits.data)).asUInt
  io.dmem.resp.zipWithIndex.foreach { case (resp, i) =>
    resp.ready := bbox.io.dmem_resp_ready(i)
    resp.bits.metadata := DontCare
  }

  io.finished := bbox.io.finished
}

class CyclotronTileBlackBox(outer: CyclotronTile)(implicit val p: Parameters)
  extends BlackBox(Map(
    "ARCH_LEN"       -> outer.archLen,
    "INST_BITS"      -> outer.muonParams.instBits,
    "IMEM_TAG_BITS"  -> outer.imemTagBits,
    "DMEM_DATA_BITS" -> outer.archLen,
    "DMEM_TAG_BITS"  -> outer.dmemTagBits,
    "NUM_LANES"      -> outer.numLanes,
  )) with HasBlackBoxResource with HasCoreParameters {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val imem_req_valid = Output(Bool())
    val imem_req_ready = Input(Bool())
    val imem_req_bits_address = Output(UInt(addressBits.W))
    val imem_req_bits_tag = Output(UInt(imemTagBits.W))
    val imem_resp_ready = Output(Bool())
    val imem_resp_valid = Input(Bool())
    val imem_resp_bits_tag = Input(UInt(imemTagBits.W))
    val imem_resp_bits_data = Input(UInt(imemDataBits.W))

    val dmem_req_valid = Output(UInt(numLanes.W))
    val dmem_req_ready = Input(UInt(numLanes.W))
    val dmem_req_bits_store = Output(UInt(numLanes.W))
    val dmem_req_bits_tag = Output(UInt((numLanes * dmemTagBits).W))
    val dmem_req_bits_address = Output(UInt((numLanes * addressBits).W))
    val dmem_req_bits_size = Output(UInt((numLanes * (new DataMemIO).req.head.bits.size.getWidth).W))
    val dmem_req_bits_data = Output(UInt((numLanes * dmemDataBits).W))
    val dmem_req_bits_mask = Output(UInt((numLanes * (dmemDataBits / 8)).W))
    val dmem_resp_ready = Output(UInt(numLanes.W))
    val dmem_resp_valid = Input(UInt(numLanes.W))
    val dmem_resp_bits_tag = Input(UInt((numLanes * dmemTagBits).W))
    val dmem_resp_bits_data = Input(UInt((numLanes * dmemDataBits).W))

    val finished = Output(Bool())
  })

  addResource("/vsrc/CyclotronTile.v")
  addResource("/vsrc/Cyclotron.vh")
  addResource("/csrc/Cyclotron.cc")
}

class CyclotronInstMem(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val imem = Flipped(new InstMemIO)
  })

  val bbox = Module(new CyclotronInstMemBlackBox)
  bbox.io.clock := clock
  bbox.io.reset := reset.asBool

  io.imem.req.ready := true.B
  bbox.io.req.valid := io.imem.req.valid
  bbox.io.req.bits.tag := io.imem.req.bits.tag
  bbox.io.req.bits.pc := io.imem.req.bits.address

  io.imem.resp.valid := bbox.io.resp.valid
  io.imem.resp.bits.tag := bbox.io.resp.bits.tag
  io.imem.resp.bits.data := bbox.io.resp.bits.inst
  io.imem.resp.bits.metadata := DontCare

  class CyclotronInstMemBlackBox(implicit val p: Parameters)
  extends BlackBox(Map(
        "ARCH_LEN"      -> p(MuonKey).archLen,
        "INST_BITS"     -> p(MuonKey).instBits,
        "IMEM_TAG_BITS" -> p(MuonKey).l0iReqTagBits
  )) with HasBlackBoxResource with HasCoreParameters {
    val io = IO(new Bundle {
      val clock = Input(Clock())
      val reset = Input(Bool())
      val req = Flipped(Valid(new Bundle {
        val tag = UInt(imemTagBits.W)
        val pc = pcT
      }))
      val resp = Valid(new Bundle {
        val tag = UInt(imemTagBits.W)
        val inst = instT
      })
    })

    addResource("/vsrc/CyclotronInstMem.v")
    addResource("/vsrc/Cyclotron.vh")
    addResource("/csrc/Cyclotron.cc")
  }
}

class CyclotronDataMem(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val dmem = Flipped(new DataMemIO)
  })

  val bbox = Module(new CyclotronDataMemBlackBox(this)(p))
  bbox.io.clock := clock
  bbox.io.reset := reset.asBool

  import Cyclotron._

  // request
  bbox.io.req_valid := VecInit(io.dmem.req.map(_.valid)).asUInt
  bbox.io.req_bits_store := VecInit(io.dmem.req.map(_.bits.store)).asUInt
  bbox.io.req_bits_tag := VecInit(io.dmem.req.map(_.bits.tag)).asUInt
  bbox.io.req_bits_address := VecInit(io.dmem.req.map(_.bits.address)).asUInt
  bbox.io.req_bits_size := VecInit(io.dmem.req.map(_.bits.size)).asUInt
  bbox.io.req_bits_data := VecInit(io.dmem.req.map(_.bits.data)).asUInt
  bbox.io.req_bits_mask := VecInit(io.dmem.req.map(_.bits.mask)).asUInt
  bbox.io.resp_ready := VecInit(io.dmem.resp.map(_.ready)).asUInt
  io.dmem.req.zipWithIndex.foreach { case (req, i) =>
    req.ready := bbox.io.req_ready(i)
  }

  // response
  io.dmem.resp.zipWithIndex.foreach { case (resp, i) =>
    resp.valid := bbox.io.resp_valid(i)
    unflatten(resp.bits.tag, bbox.io.resp_bits_tag, i)
    unflatten(resp.bits.data, bbox.io.resp_bits_data, i)
    resp.bits.metadata := DontCare
  }

class CyclotronDataMemBlackBox(outer: CyclotronDataMem)(implicit val p: Parameters)
  extends BlackBox(Map(
        "ARCH_LEN"       -> outer.archLen,
        "DMEM_DATA_BITS" -> outer.archLen,
        "DMEM_TAG_BITS"  -> outer.dmemTagBits,
        "NUM_LANES"      -> outer.numLanes,
  )) with HasBlackBoxResource with HasCoreParameters {
    val io = IO(new Bundle {
      val clock = Input(Clock())
      val reset = Input(Bool())
      val req_valid = Input(UInt(numLanes.W))
      val req_ready = Output(UInt(numLanes.W))
      val req_bits_store = Input(UInt(numLanes.W))
      val req_bits_tag = Input(UInt((numLanes * dmemTagBits).W))
      val req_bits_address = Input(UInt((numLanes * addressBits).W))
      val req_bits_size = Input(UInt((numLanes * (new DataMemIO).req.head.bits.size.getWidth).W))
      val req_bits_data = Input(UInt((numLanes * dmemDataBits).W))
      val req_bits_mask = Input(UInt((numLanes * (dmemDataBits / 8)).W))
      val resp_ready = Input(UInt(numLanes.W))
      val resp_valid = Output(UInt(numLanes.W))
      val resp_bits_tag = Output(UInt((numLanes * dmemTagBits).W))
      val resp_bits_data = Output(UInt((numLanes * dmemDataBits).W))
    })

    addResource("/vsrc/CyclotronDataMem.v")
    addResource("/vsrc/Cyclotron.vh")
    addResource("/csrc/Cyclotron.cc")
  }
}

/** If `tick` is true, advance cyclotron sim by one tick inside the difftest
 *  logic.  Set to false when some other module does the tick, e.g.
 *  separate cyclotron frontend */
class CyclotronDiffTest(tick: Boolean = true)
(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val trace = Flipped(Valid(new TraceIO))
  })

  val cbox = Module(new CyclotronDiffTestBlackBox(tick)(p))
  cbox.io.clock := clock
  cbox.io.reset := reset.asBool

  cbox.io.trace.valid := io.trace.valid
  cbox.io.trace.pc := io.trace.bits.pc
  cbox.io.trace.warpId := io.trace.bits.warpId
  cbox.io.trace.tmask := io.trace.bits.tmask
  (cbox.io.trace.regs zip io.trace.bits.regs).foreach { case (boxtr, tr) =>
    boxtr.enable := tr.enable
    boxtr.address := tr.address
    boxtr.data := tr.data.asUInt
  }

  class CyclotronDiffTestBlackBox(tick: Boolean)(implicit val p: Parameters)
  extends BlackBox(Map(
        "ARCH_LEN"     -> p(MuonKey).archLen,
        "NUM_WARPS"    -> p(MuonKey).numWarps,
        "NUM_LANES"    -> p(MuonKey).numLanes,
        "REG_BITS"     -> Isa.regBits,
        "SIM_TICK"     -> (if (tick) 1 else 0),
  )) with HasBlackBoxResource with HasCoreParameters {
    val io = IO(new Bundle {
      val clock = Input(Clock())
      val reset = Input(Bool())
      val trace = Input(new TraceVerilogIO)
    })

    addResource("/vsrc/CyclotronDiffTest.v")
    addResource("/vsrc/Cyclotron.vh")
    addResource("/csrc/Cyclotron.cc")
  }
}

class CyclotronMemBlackBox(implicit p: Parameters) extends CyclotronBlackBox
with HasBlackBoxResource {

  val archLen = p(MuonKey).archLen
  val lsuLanes = p(MuonKey).lsu.numLsuLanes
  val dataWidth = lsuLanes * archLen
  val maskWidth = lsuLanes

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val req_ready = Output(Bool())
    val req_valid = Input(Bool())

    val req_store = Input(Bool())
    // Per-lane addresses packed into dataWidth
    val req_address = Input(UInt(dataWidth.W))
    val req_tag = Input(UInt(32.W))
    val req_data = Input(UInt(dataWidth.W))
    // Per-lane mask, 1 bit per lane
    val req_mask = Input(UInt(maskWidth.W))

    val resp_ready = Input(Bool())
    val resp_valid = Output(Bool())

    val resp_tag = Output(UInt(32.W))
    val resp_data = Output(UInt(dataWidth.W))
    // Per-lane response valids
    val resp_valids = Output(UInt(maskWidth.W))
  })

  addResource("/vsrc/CyclotronMem.v")
  addResource("/vsrc/Cyclotron.vh")
  addResource("/csrc/Cyclotron.cc")
}

class TraceVerilogIO()(implicit p: Parameters) extends CoreBundle()(p) {
  val valid = Bool()
  val pc = pcT
  val warpId = widT
  val tmask = tmaskT
  val regs = Vec(Isa.maxNumRegs, new Bundle {
    val enable = Bool()
    val address = pRegT
    val data = UInt((muonParams.numLanes * muonParams.archLen).W)
  })
}

/** Captures Muon's instruction and memory traces and stores into a database. */
class Tracer(clusterId: Int = 0, coreId: Int = 0)(implicit p: Parameters)
extends CoreModule {
  val io = IO(new Bundle {
    val trace = Flipped(Valid(new TraceIO))
  })

  val cbox = Module(new TracerBlackBox()(p))
  cbox.io.clock := clock
  cbox.io.reset := reset.asBool

  cbox.io.trace.valid := io.trace.valid
  cbox.io.trace.pc := io.trace.bits.pc
  cbox.io.trace.warpId := io.trace.bits.warpId
  cbox.io.trace.tmask := io.trace.bits.tmask
  (cbox.io.trace.regs zip io.trace.bits.regs).foreach { case (boxtr, tr) =>
    boxtr.enable := tr.enable
    boxtr.address := tr.address
    boxtr.data := tr.data.asUInt
  }

  class TracerBlackBox()(implicit val p: Parameters)
  extends BlackBox(Map(
        "CLUSTER_ID" -> clusterId,
        "CORE_ID" -> coreId,
        "ARCH_LEN"     -> p(MuonKey).archLen,
        "NUM_WARPS"    -> p(MuonKey).numWarps,
        "NUM_LANES"    -> p(MuonKey).numLanes,
        "REG_BITS"     -> Isa.regBits,
  )) with HasBlackBoxResource with HasCoreParameters {
    val io = IO(new Bundle {
      val clock = Input(Clock())
      val reset = Input(Bool())
      val trace = Input(new TraceVerilogIO)
    })

    addResource("/vsrc/Tracer.v")
    addResource("/vsrc/Cyclotron.vh")
    addResource("/csrc/Cyclotron.cc")
  }
}

/** Collects Muon's performance counters and prints a performance analysis report at RTL sim. */
class Profiler(clusterId: Int = 0, coreId: Int = 0)(implicit p: Parameters)
extends CoreModule {
  val io = IO(new Bundle {
    val finished = Input(Bool())
    val perf = Flipped(new PerfIO)
  })

  val bbox = Module(new ProfilerBlackBox()(p))
  bbox.io.clock := clock
  bbox.io.reset := reset.asBool

  bbox.io.finished := io.finished
  bbox.io.instRetired := io.perf.backend.instRetired
  bbox.io.cycles := io.perf.backend.cycles
  bbox.io.cyclesDecoded := io.perf.backend.cyclesDecoded
  bbox.io.cyclesEligible := io.perf.backend.cyclesEligible
  bbox.io.cyclesIssued := io.perf.backend.cyclesIssued
  bbox.io.perWarp_cyclesDecoded := VecInit(io.perf.backend.perWarp.map(_.cyclesDecoded)).asUInt
  bbox.io.perWarp_cyclesIssued := VecInit(io.perf.backend.perWarp.map(_.cyclesIssued)).asUInt
  bbox.io.perWarp_stallsWAW := VecInit(io.perf.backend.perWarp.map(_.stallsWAW)).asUInt
  bbox.io.perWarp_stallsWAR := VecInit(io.perf.backend.perWarp.map(_.stallsWAR)).asUInt
  bbox.io.perWarp_stallsBusy := VecInit(io.perf.backend.perWarp.map(_.stallsBusy)).asUInt

  class ProfilerBlackBox()(implicit val p: Parameters)
  extends BlackBox(Map(
        "COUNTER_WIDTH" -> Perf.counterWidth,
        "NUM_WARPS" -> muonParams.numWarps,
        "CLUSTER_ID" -> clusterId,
        "CORE_ID" -> coreId,
  )) with HasBlackBoxResource with HasCoreParameters {
    val io = IO(new Bundle {
      val clock = Input(Clock())
      val reset = Input(Bool())
      val finished = Input(Bool())
      val instRetired = Input(UInt(Perf.counterWidth.W))
      val cycles = Input(UInt(Perf.counterWidth.W))
      val cyclesDecoded = Input(UInt(Perf.counterWidth.W))
      val cyclesEligible = Input(UInt(Perf.counterWidth.W))
      val cyclesIssued = Input(UInt(Perf.counterWidth.W))
      val perWarp_cyclesDecoded = Input(UInt((muonParams.numWarps * Perf.counterWidth).W))
      val perWarp_cyclesIssued = Input(UInt((muonParams.numWarps * Perf.counterWidth).W))
      val perWarp_stallsWAW = Input(UInt((muonParams.numWarps * Perf.counterWidth).W))
      val perWarp_stallsWAR = Input(UInt((muonParams.numWarps * Perf.counterWidth).W))
      val perWarp_stallsBusy = Input(UInt((muonParams.numWarps * Perf.counterWidth).W))
    })

    addResource("/vsrc/Profiler.v")
    addResource("/vsrc/Cyclotron.vh")
    addResource("/csrc/Cyclotron.cc")
  }
}
