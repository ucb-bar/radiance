package radiance.muon.backend.fp

import chisel3._
import chisel3.experimental._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

object FPRoundingMode extends ChiselEnum {
  val RNE = Value("b000".U)
  val RTZ = Value("b001".U)
  val RDN = Value("b010".U)
  val RUP = Value("b011".U)
  val RMM = Value("b100".U)
  val ROD = Value("b101".U)
  val DYN = Value("b111".U)
}

object FSGNMode extends ChiselEnum {
  val SGNJ  = Value("b000".U)
  val SGNJN = Value("b001".U)
  val SGNJX = Value("b010".U)
  val NONE  = Value("b011".U)
  val _w    = Value("b111".U) // to force 3 bits
}

object FMinMaxMode extends ChiselEnum {
  val MIN = Value("b000".U)
  val MAX = Value("b001".U)
  val _w  = Value("b111".U)
}

object FCMPMode extends ChiselEnum {
  val LE = Value("b000".U)
  val LT = Value("b001".U)
  val EQ = Value("b010".U)
  val _w = Value("b111".U)
}

object FPUOp extends ChiselEnum {
  val FMADD    = Value("b0000_0".U)
  val FMSUB    = Value("b0000_1".U)
  val FMNSUB   = Value("b0001_0".U)
  val FMNADD   = Value("b0001_1".U)
  val ADD      = Value("b0010_0".U)
  val SUB      = Value("b0010_1".U)
  val MUL      = Value("b0011_0".U)
  val DIV      = Value("b0100_0".U)
  val SQRT     = Value("b0101_0".U)
  val SGNJ     = Value("b0110_0".U)
  // val SGNJSEXT = Value("b0110_1".U)
  val MINMAX   = Value("b0111_0".U)
  val CMP      = Value("b1000_0".U)
  val CLASSIFY = Value("b1001_0".U)
  val F2F      = Value("b1010_0".U) // float <-> float
  val F2SI     = Value("b1011_0".U) // signed integer
  val F2UI     = Value("b1011_1".U) // unsigned integer
  val SI2F     = Value("b1100_0".U)
  val UI2F     = Value("b1100_1".U)
  // CPKAB01
  // CPKAB23
  // CPKCD45
  // CPKCD67
}

object FPFormat extends ChiselEnum {
  val FP32 = Value("b000".U)
  val FP64 = Value("b001".U) // unsupported
  val FP16 = Value("b010".U)
  val E5M2 = Value("b011".U) // unsupported
  val _w   = Value("b111".U)
}

object IntFormat extends ChiselEnum {
  val INT8  = Value("b00".U) // unsupported
  val INT16 = Value("b01".U)
  val INT32 = Value("b10".U)
  val INT64 = Value("b11".U) // unsupported
}

class CVFPUReq(numFp16Lanes: Int = 16, tagWidth: Int = 1) extends Bundle {
  val roundingMode = FPRoundingMode()
  val op = FPUOp() // op mod is lsb
  val srcFormat = FPFormat()
  val dstFormat = FPFormat()
  val intFormat = IntFormat()
  val tag = UInt(tagWidth.W)
  val simdMask = UInt(numFp16Lanes.W)
  val operands = Vec(3, UInt((numFp16Lanes * 16).W))
}

class CVFPUResp(numFp16Lanes: Int = 16, tagWidth: Int = 1) extends Bundle {
  val result = UInt((numFp16Lanes * 16).W)
  val status = UInt(5.W) // {invalid, div by zero, overflow, underflow, inexact}
  val tag = UInt(tagWidth.W)
}

class CVFPU(
  numFp16Lanes: Int = 16,
  tagWidth: Int = 1,
  isDivSqrtUnit: Boolean = false,
)(implicit p: Parameters) extends BlackBox(
  Map(
    "WIDTH" -> numFp16Lanes * 16,
    "LANES" -> numFp16Lanes,
    "TAG_WIDTH" -> tagWidth,
    "IS_DIVSQRT_UNIT" -> { if (isDivSqrtUnit) 1 else 0 }
  )
) with HasBlackBoxResource with HasBlackBoxPath {

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())

    val req = Flipped(Decoupled(new CVFPUReq(numFp16Lanes, tagWidth)))

    val resp = Decoupled(new CVFPUResp(numFp16Lanes, tagWidth))

    val flush = Input(Bool())
    val busy = Output(Bool())
  })

  addResource("/vsrc/cvfpu/src/common_cells/include/common_cells/registers.svh")
  addResource("/vsrc/cvfpu/src/fpnew_pkg.sv")
  addResource("/vsrc/vortex/third_party/fpnew/src/common_cells/src/cf_math_pkg.sv")
  addResource("/vsrc/cvfpu/src/fpu_div_sqrt_mvp/hdl/control_mvp.sv")

  addResource("/vsrc/CVFPU.v")

  addResource("/vsrc/cvfpu/src/fpnew_cast_multi.sv")
  addResource("/vsrc/cvfpu/src/fpnew_classifier.sv")
  addResource("/vsrc/cvfpu/src/fpnew_divsqrt_multi.sv")
  addResource("/vsrc/cvfpu/src/fpnew_divsqrt_th_32.sv")
  addResource("/vsrc/cvfpu/src/fpnew_fma.sv")
  addResource("/vsrc/cvfpu/src/fpnew_fma_multi.sv")
  addResource("/vsrc/cvfpu/src/fpnew_noncomp.sv")
  addResource("/vsrc/cvfpu/src/fpnew_opgroup_block.sv")
  addResource("/vsrc/cvfpu/src/fpnew_opgroup_fmt_slice.sv")
  addResource("/vsrc/cvfpu/src/fpnew_opgroup_multifmt_slice.sv")
  addResource("/vsrc/cvfpu/src/fpnew_rounding.sv")
  addResource("/vsrc/cvfpu/src/fpnew_top.sv")
  addResource("/vsrc/cvfpu/src/fpu_div_sqrt_mvp/hdl/defs_div_sqrt_mvp.sv")
  addResource("/vsrc/cvfpu/src/fpu_div_sqrt_mvp/hdl/div_sqrt_mvp_wrapper.sv")
  addResource("/vsrc/cvfpu/src/fpu_div_sqrt_mvp/hdl/div_sqrt_top_mvp.sv")
  addResource("/vsrc/cvfpu/src/fpu_div_sqrt_mvp/hdl/iteration_div_sqrt_mvp.sv")
  addResource("/vsrc/cvfpu/src/fpu_div_sqrt_mvp/hdl/norm_div_sqrt_mvp.sv")
  addResource("/vsrc/cvfpu/src/fpu_div_sqrt_mvp/hdl/nrbd_nrsc_mvp.sv")
  addResource("/vsrc/cvfpu/src/fpu_div_sqrt_mvp/hdl/preprocess_mvp.sv")
  addResource("/vsrc/vortex/third_party/fpnew/src/common_cells/src/lzc.sv")
  addResource("/vsrc/vortex/third_party/fpnew/src/common_cells/src/rr_arb_tree.sv")
}
