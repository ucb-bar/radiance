package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

case object LoadStoreUnit

case class LoadStoreUnitParams (
    val numLsuLanes: Int = 16,
    val numLdqEntries: Int = 4,
    val numStqEntries: Int = 4
) {
    // extra bit for circular wraparound
    val ldqIndexBits = log2Up(numLdqEntries) + 1
    val stqIndexBits = log2Up(numStqEntries) + 1
    val queueIndexBits = math.max(ldqIndexBits, stqIndexBits)
}

object AddressSpace extends ChiselEnum {
    val globalMemory = Value
    val sharedMemory = Value
}

object MemOp extends ChiselEnum {
    val loadByte, loadHalf, loadWord = Value
    val storeByte, storeHalf, storeWord = Value
    val amoSwap, amoAdd, amoAnd, amoOr, amoXor, amoMax, amoMin = Value
}

// Load and Store Queues must allocate slots in program order and produce an index for the allocated slot.
// This data must be held in reservation station and given to LSU when a memory instruction issues 
// (possibly out-of-order). 
class LsuQueueReservationInterface(implicit p: MuonCoreParams) extends Bundle {
    val requestValid = Input(Bool())
    val addressSpace = Input(AddressSpace())
    val op = Input(MemOp())
    val warpId = Input(UInt(p.warpIdWidth.W))
    
    val outputValid = Output(Bool())
    val queueIndex = Output(UInt(p.lsu.queueIndexBits.W))
}

class LoadStoreQueue(implicit p: MuonCoreParams) extends Module {
    val io = IO(new Bundle {
        val lsuQueueReservationInterface = new LsuQueueReservationInterface
        
        val lsuRequest = Input(new LsuRequest)
    })

    class PerWarpLoadQueue extends Module {
        val io = IO(new Bundle {
            val ldqHead = Output(UInt(p.lsu.ldqIndexBits.W))
            val ldqTail = Output(UInt(p.lsu.ldqIndexBits.W))

            val stqHead = Input(UInt(p.lsu.stqIndexBits.W))
            val stqTail = Input(UInt(p.lsu.stqIndexBits.W))

            val full = Output(Bool())
            val enqueue = Input(Bool())

            val receivedOperands = Input(Valid(UInt(p.lsu.ldqIndexBits.W)))
            val receivedMemResponse = Input(Valid(UInt(p.lsu.ldqIndexBits.W)))
        })

        val head = RegInit(0.U(p.lsu.ldqIndexBits.W))
        val tail = RegInit(0.U(p.lsu.ldqIndexBits.W))

        // entries need to contain pointer to tail of other queue at allocation time, 
        // and whether operands were received from reservation station
        val valid = RegInit(VecInit.fill(p.lsu.numLdqEntries)(false.B))
        val stqIndex = RegInit(VecInit.fill(p.lsu.numLdqEntries)(0.U(p.lsu.stqIndexBits.W)))
        val operandsReady = RegInit(VecInit.fill(p.lsu.numLdqEntries)(false.B))

        val readyLoads = Wire(Vec(p.lsu.numLdqEntries, Bool()))
        for (i <- readyLoads) {
            val olderStoresRetired = (io.stqHead - stqIndex(i)).apply(p.lsu.stqIndexBits-1) 
            readyLoads(i) := valid(i) && operandsReady(i) && olderStoresRetired
        }

        // update head
        // TODO: should this be faster
        when (!valid(head)) {
            head := head + 1.U
        }
        
    }

    for (warp <- 0 until p.numWarps) {

    }
}

// Execute interface
class LsuRequest(implicit p: MuonCoreParams) extends Bundle {
    val addressSpace = AddressSpace()
    val warpId = UInt(p.warpIdWidth.W)
    val tmask = Vec(p.numLanes, Bool())
    val op = MemOp()

    val address = Vec(p.numLanes, UInt(p.xLen.W))
    val data = Vec(p.numLanes, UInt(p.xLen.W))
}

// Writeback interface
class LsuResponse(implicit p: MuonCoreParams) extends Bundle {
    val warpId = UInt(p.warpIdWidth.W)
    val tmask = Vec(p.numLanes, Bool())
    val op = MemOp()

    val data = Vec(p.numLanes, UInt(p.xLen.W))
}

// Downstream memory interface
class MemRequest(implicit p: MuonCoreParams) extends Bundle {
    
}

class MemResponse(implicit p: MuonCoreParams) extends Bundle {

}

class LoadStoreUnit extends Module {
    implicit val p = MuonCoreParams()

    val io = IO(new Bundle {
        
    })
}
