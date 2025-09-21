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
    val ldqIndexBits = log2Up(numLdqEntries)
    val stqIndexBits = log2Up(numStqEntries)
    val ldqCircIndexBits = ldqIndexBits + 1
    val stqCircIndexBits = stqIndexBits + 1
    
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

    class PerWarpQueue(
        loadQueue: Boolean
    ) extends Module {
        // helper functions for circular fifo indices
        val wrapBit = (x: UInt) => x(x.getWidth - 1)
        val msb = wrapBit
        val idxBits = (x: UInt) => x(x.getWidth - 2, 0)

        val entries            = if (loadQueue) { p.lsu.numLdqEntries    } else { p.lsu.numStqEntries    }
        val circIndexBits      = if (loadQueue) { p.lsu.ldqCircIndexBits } else { p.lsu.stqCircIndexBits }
        val otherCircIndexBits = if (loadQueue) { p.lsu.stqCircIndexBits } else { p.lsu.ldqCircIndexBits }
        val indexBits          = if (loadQueue) { p.lsu.ldqIndexBits     } else { p.lsu.stqIndexBits     }

        val io = IO(new Bundle {
            val myHead = Output(UInt(circIndexBits.W))
            val myTail = Output(UInt(circIndexBits.W))

            val otherHead = Input(UInt(otherCircIndexBits.W))
            val otherTail = Input(UInt(otherCircIndexBits.W))

            val full = Output(Bool())
            val enqueue = Input(Bool())

            val receivedOperands = Flipped(Valid(UInt(indexBits.W)))
            val receivedMemResponse = Flipped(Valid(UInt(indexBits.W)))
            val requestIndex = Decoupled(UInt(indexBits.W))
        })

        val head = RegInit(0.U(circIndexBits.W))
        val tail = RegInit(0.U(circIndexBits.W))

        val full = (wrapBit(head) =/= wrapBit(tail)) && (idxBits(head) === idxBits(tail))
        val empty = (head === tail)

        io.myHead := head
        io.myTail := tail
        io.full := full

        // entries need to contain pointer to tail of other queue at allocation time, 
        // and whether operands were received from reservation station
        val valid = RegInit(VecInit.fill(entries)(false.B))
        val otherQueueTailInit = VecInit.fill(entries)(0.U(otherCircIndexBits.W))
        val otherQueueTail = RegInit(otherQueueTailInit)
        val operandsReady = RegInit(VecInit.fill(entries)(false.B))
        val issued = RegInit(VecInit.fill(entries)(false.B))

        // allocation logic
        when (io.enqueue) {
            assert(!full, "overflow of per-warp queue")
            
            valid(idxBits(tail)) := true.B
            otherQueueTail(idxBits(tail)) := io.otherTail
            operandsReady(idxBits(tail)) := false.B
            issued(idxBits(tail)) := false.B
            
            tail := tail + 1.U
        }

        // mark when operands received from reservation station
        when (io.receivedOperands.valid) {
            assert(valid(io.receivedOperands.bits), "invalid index from reservation station")

            operandsReady(io.receivedOperands.bits) := true.B
        }

        // find entries ready to issue a mem request
        if (loadQueue) {
            val readyLoads = Wire(Vec(entries, Bool()))
            for (i <- 0 until entries) {
                val olderStoresRetired = msb(io.otherHead - otherQueueTail(i)) 
                readyLoads(i) := valid(i) && operandsReady(i) && olderStoresRetired && !issued(i)
            }

            // issue request
            val anyLoadReady = readyLoads.asUInt.orR
            val readyLoadIndex = PriorityEncoder(readyLoads)

            io.requestIndex.valid := anyLoadReady
            io.requestIndex.bits := readyLoadIndex

            when (io.requestIndex.fire) {
                issued(readyLoadIndex) := true.B
            }
        }
        else {
            val olderLoadsRetired = msb(io.otherHead - otherQueueTail(idxBits(head)))

            val readyStore = {
                valid(idxBits(head)) &&
                operandsReady(idxBits(head)) &&
                olderLoadsRetired &&
                !issued(idxBits(head))
            }
            val readyStoreIndex = idxBits(head)

            io.requestIndex.valid := readyStore
            io.requestIndex.bits := readyStoreIndex

            when (io.requestIndex.fire) {
                issued(readyStoreIndex) := true.B
            }
        }

        // deallocate once response received
        when (io.receivedMemResponse.valid) {
            valid(io.receivedMemResponse.bits) := false.B
        }

        // update head
        // TODO: should this be faster?
        when (!empty && !valid(idxBits(head))) {
            head := head + 1.U
        }
    }

    for (warp <- 0 until p.numWarps) {
        val warpLoadQueue = Module(new PerWarpQueue(true))
        val warpStoreQueue = Module(new PerWarpQueue(false))

        warpLoadQueue.io.otherHead := warpStoreQueue.io.myHead
        warpLoadQueue.io.otherTail := warpStoreQueue.io.myTail
        warpStoreQueue.io.otherHead := warpLoadQueue.io.myHead
        warpStoreQueue.io.otherTail := warpLoadQueue.io.myTail
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
class LsuMemRequest(implicit p: MuonCoreParams) extends Bundle {
    
}

class LsuMemResponse(implicit p: MuonCoreParams) extends Bundle {

}

class LoadStoreUnit extends Module {
    implicit val p = MuonCoreParams()

    val io = IO(new Bundle {
        
    })
}
