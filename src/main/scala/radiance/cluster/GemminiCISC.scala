package radiance.cluster

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import freechips.rocketchip.resources.BigIntHexContext

class GemminiCISC(accSlave: Option[AccNode.Slave],
                  busy: Bool,
                  tileParams: GemminiTileParams) {

  val instCounter = Counter(4)
  val ciscValid = RegInit(false.B)
  val ciscArgs = RegInit(0.U(24.W))
  val ciscId = RegInit(0.U(8.W))
  val ciscInstT = new Bundle {
    val inst = UInt(32.W)
    val rs1 = UInt(64.W)
    val rs2 = UInt(64.W)
  }
  val ciscInst = Wire(ciscInstT)
  val startsLoop = WireInit(false.B)

  val accCommandQueue = Module(new Queue(UInt(32.W), 4, false, true))
  accCommandQueue.io.deq.ready := !ciscValid

  when (accCommandQueue.io.enq.fire) {
    val enqId = accCommandQueue.io.enq.bits(6, 0)
    startsLoop := VecInit(Seq(0, 1, 2, 9, 10, 12).map { x => enqId === x.U }).asUInt.orR
  }

  when (accCommandQueue.io.deq.fire) {
    ciscValid := true.B
    ciscId := accCommandQueue.io.deq.bits(7, 0)
    ciscArgs := accCommandQueue.io.deq.bits(31, 8)
    instCounter.reset()
  }

  assert(!accCommandQueue.io.enq.valid || accCommandQueue.io.enq.ready, "cisc command queue full")

  accSlave.map(_.in.head._1) match {
    case Some(as) => {
      accCommandQueue.io.enq.bits := as.cmd.bits
      accCommandQueue.io.enq.valid := as.cmd.valid
      as.status := RegNext(busy).asUInt
    }
    case None => // mmio will handle
  }


  def microcodeEntry[T <: Data](insts: Seq[T]): T = {
    when (instCounter.value === (insts.size - 1).U) {
      ciscValid := false.B
      instCounter.reset()
    }.otherwise {
      instCounter.inc()
    }
    VecInit(insts)(instCounter.value)
  }

  ciscInst := 0.U.asTypeOf(ciscInstT)

  val (tileSizeM, tileSizeN, tileSizeK) = tileParams.tileSize match {
    case Left(v: (Int, Int, Int)) => v
    case Right(v: Int) => (v, v, v)
  }
  val config = tileParams.gemminiConfig
  val spadHexadecile = config.sp_bank_entries * config.sp_banks / 16

  // TODO: as a temporary hack, bit 7 of the cisc opcode
  // TODO: will force the tile size to be a square base on M.

  val rectBoundsInst = ciscInstT.Lit(_.inst -> 0x1220b07b.U, _.rs1 -> 0.U,
    _.rs2 -> (tileSizeM | (tileSizeN << 16) | (BigInt(tileSizeK) << 32)).U)
  val squareBoundsInst = ciscInstT.Lit(_.inst -> 0x1220b07b.U, _.rs1 -> 0.U,
    _.rs2 -> (tileSizeM | (tileSizeM << 16) | (BigInt(tileSizeM) << 32)).U)
  val boundsInst = Mux(ciscId(7), squareBoundsInst, rectBoundsInst)
  val nopInst = ciscInstT.Lit(_.inst -> 0.U, _.rs1 -> 0.U, _.rs2 -> 0.U)

  def genStrideInst(tileA: UInt, tileB: UInt) = {
    val inst = Wire(ciscInstT)
    inst.inst := 0x3020b07b.U
    inst.rs1 := tileA * spadHexadecile.U          // A should be stored from the start of this block
    inst.rs2 := (tileB + 1.U) * spadHexadecile.U  // B should be stored up till the end of this block
    inst
  }

  def genAccSkipInst(accumulate: UInt, skips: UInt) = {
    val inst = Wire(ciscInstT)
    inst.inst := 0x1020b07b.U
    inst.rs1 := accumulate
    inst.rs2 := skips
    inst
  }

  // println(s"gemmini cisc initialized with DIM=${config.DIM}, tileSize=${tileSizeM},${tileSizeN},${tileSizeK}")
  // println(f"boundsInst=${rectBoundsInst.litValue}%x, hexadecile=${spadHexadecile}")

  when (ciscValid) {
    switch (ciscId(6, 0)) {
      is (0.U) { // compute on given hexadeciles
        val strideInst = genStrideInst(ciscArgs(7, 0), ciscArgs(15, 8))
        val accSkipInst = genAccSkipInst(ciscArgs(16), 0x2b8.U)
        ciscInst := microcodeEntry(Seq(boundsInst, strideInst, accSkipInst))
      } // replaces opcode 0: (a, b, accum) = (0, 2, 0), op 1 = (0, 2, 1), op 2 = (1, 3, 1), op 3 = (1, 3, 0)
      is (1.U) { // compute on given hexadeciles and mvout to spad
        val strideInst = genStrideInst(ciscArgs(7, 0), ciscArgs(15, 8))
        // note that accumulation is disabled
        val accSkipInst = genAccSkipInst(0.U, ((ciscArgs(23, 16) * spadHexadecile.U) << 32).asUInt | 0x238.U)
        ciscInst := microcodeEntry(Seq(boundsInst, strideInst, accSkipInst))
      }
      is (2.U) {
        ciscInst := microcodeEntry(Seq(nopInst))
      } // no actual invocation, fake job placeholder
      is (8.U) { // set a, b stride
        val inst = Wire(ciscInstT)
        inst.inst := 0x1820b07b.U
        inst.rs1 := ciscArgs(11, 0)  // a
        inst.rs2 := ciscArgs(23, 12) // b
        ciscInst := microcodeEntry(Seq(inst))
      }
      is (9.U) { // move out to scratchpad
        val accSkipInst = genAccSkipInst(0.U, ((ciscArgs(7, 0) * spadHexadecile.U) << 32).asUInt | 0x278.U)
        ciscInst := microcodeEntry(Seq(boundsInst, accSkipInst))
      }
      is (10.U) { // load to scratchpad hexadeciles
        val strideInst = genStrideInst(ciscArgs(7, 0), ciscArgs(15, 8))
        val accSkipInst = genAccSkipInst(1.U, 0x2e0.U)
        ciscInst := microcodeEntry(Seq(boundsInst, strideInst, accSkipInst))
      } // replaces opcode 10: (a, b) = (0, 2), opcode 11 = (1, 3), opcode 12 = (0, 0), opcode 13 = (2, 2)
      is (11.U) { // set d, c stride
        val inst = Wire(ciscInstT)
        inst.inst := 0x1a20b07b.U
        inst.rs1 := ciscArgs(11, 0)  // d
        inst.rs2 := ciscArgs(23, 12) // c
        ciscInst := microcodeEntry(Seq(inst))
      }
      is (12.U) { // store to gmem
        val accSkipInst = genAccSkipInst(0.U, 0x78.U)
        ciscInst := microcodeEntry(Seq(boundsInst, accSkipInst))
      }

      is (16.U) { // unused, configure gemmini
        ciscInst := microcodeEntry(Seq(
          ciscInstT.Lit(_.inst -> 0x0020b07b.U, _.rs1 -> x"3f800000_00080101".U, _.rs2 -> 0.U),
          ciscInstT.Lit(_.inst -> 0x0020b07b.U, _.rs1 -> x"3f800000_00010004".U, _.rs2 -> x"10000_00000000".U),
          ciscInstT.Lit(_.inst -> 0x0020b07b.U, _.rs1 -> 0x2.U, _.rs2 -> x"3f800000_00000000".U)
        ))
      }
    }
  }

}
