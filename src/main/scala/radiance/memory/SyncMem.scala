package radiance.memory
import chisel3._
import chisel3.util._

// modified from gemmini's two port sync mem

// Read-during-write to one address: FPGA block RAM and SRAM macros may return the old word whatever SyncReadMem's
// ruw says (VCS honours WriteFirst, FireSim does not), so forward the written bytes into the read data explicitly.
object ReadDuringWriteForward {
  def apply(rdata: Vec[UInt], ren: Bool, raddr: UInt, wen: Bool, waddr: UInt, wdata: Vec[UInt], mask: UInt): Vec[UInt] = {
    val hit = ren && wen && raddr === waddr
    val hitD = RegNext(hit, false.B)
    val fwdData = RegEnable(wdata, hit)
    val fwdMask = RegEnable(mask, hit)
    VecInit(rdata.indices.map(i => Mux(hitD && fwdMask(i), fwdData(i), rdata(i))))
  }
}
class TwoPortSyncMem[T <: Data](
  n: Int, t: T,
  maskedUnitWidth: Int = 8,
  name: String
) extends Module {
  val maskWidth = t.getWidth / maskedUnitWidth
  val io = IO(new Bundle {
    val waddr = Input(UInt((log2Ceil(n) max 1).W))
    val raddr = Input(UInt((log2Ceil(n) max 1).W))
    val wdata = Input(t)
    val rdata = Output(t)
    val wen = Input(Bool())
    val ren = Input(Bool())
    val mask = Input(UInt(maskWidth.W))
  })

  when (io.wen && io.ren && io.raddr === io.waddr) {
    printf("WARNING: read and write collided at address 0x%x\n", io.raddr)
  }

  val maskElem = UInt(maskedUnitWidth.W)
  val memT = Vec(maskWidth, maskElem)
  val mem = SyncReadMem(n, memT).suggestName(name)

  io.rdata := ReadDuringWriteForward(mem.read(io.raddr, io.ren), io.ren, io.raddr,
    io.wen, io.waddr, io.wdata.asTypeOf(memT), io.mask).asTypeOf(t)

  when (io.wen) {
    mem.write(io.waddr, io.wdata.asTypeOf(memT), io.mask.asBools)
  }
}

class TwoReadOneWriteSyncMem[T <: Data](n: Int, t: T, maskedUnitWidth: Int = 8) extends Module {
  val maskWidth = t.getWidth / maskedUnitWidth
  val io = IO(new Bundle {
    val waddr = Input(UInt((log2Ceil(n) max 1).W))
    val raddr0 = Input(UInt((log2Ceil(n) max 1).W))
    val raddr1 = Input(UInt((log2Ceil(n) max 1).W))
    val wdata = Input(t)
    val rdata0 = Output(t)
    val rdata1 = Output(t)
    val wen = Input(Bool())
    val ren0 = Input(Bool())
    val ren1 = Input(Bool())
    val mask = Input(UInt(maskWidth.W))
  })

  when (io.wen && io.ren0 && io.raddr0 === io.waddr) {
    printf("WARNING: read0 and write collided at address 0x%x\n", io.raddr0)
  }
  when (io.wen && io.ren1 && io.raddr1 === io.waddr) {
    printf("WARNING: read1 and write collided at address 0x%x\n", io.raddr1)
  }

  val maskElem = UInt(maskedUnitWidth.W)
  val memT = Vec(maskWidth, maskElem)
  val mem0 = SyncReadMem(n, memT)
  val mem1 = SyncReadMem(n, memT)

  io.rdata0 := ReadDuringWriteForward(mem0.read(io.raddr0, io.ren0), io.ren0, io.raddr0,
    io.wen, io.waddr, io.wdata.asTypeOf(memT), io.mask).asTypeOf(t)
  io.rdata1 := ReadDuringWriteForward(mem1.read(io.raddr1, io.ren1), io.ren1, io.raddr1,
    io.wen, io.waddr, io.wdata.asTypeOf(memT), io.mask).asTypeOf(t)

  when (io.wen) {
    mem0.write(io.waddr, io.wdata.asTypeOf(memT), io.mask.asBools)
    mem1.write(io.waddr, io.wdata.asTypeOf(memT), io.mask.asBools)
  }
}


object TwoPortSyncMem {
  def apply[T <: Data](
    n: Int,
    t: T,
    maskedUnitWidth: Int = 8,
    name: String = "mem"
  ): TwoPortSyncMem[T] = {
    Module(new TwoPortSyncMem[T](n, t, maskedUnitWidth, name))
  }
}

object TwoReadOneWriteSyncMem {
  def apply[T <: Data](n: Int, t: T, maskedUnitWidth: Int = 8): TwoReadOneWriteSyncMem[T] = {
    Module(new TwoReadOneWriteSyncMem[T](n, t, maskedUnitWidth))
  }
}

object MultiReadOneWriteSRAM {
  def apply[T <: Data](
    n: Int, 
    t: T, 
    numReadPorts: Int = 2, 
    masked: Boolean = false
  ): SRAMInterface[T] = {

    val sramInterface = Wire(new SRAMInterface(n, t, numReadPorts, numWritePorts = 1, numReadwritePorts = 0, masked))
    for (i <- 0 until numReadPorts) {
      val inner = SRAM(n, t, numReadPorts = 1, numWritePorts = 1, numReadwritePorts = 0)
      sramInterface.readPorts(i) <> inner.readPorts(0)
      inner.writePorts(0) := sramInterface.writePorts(0)
    }

    sramInterface
  }
}
