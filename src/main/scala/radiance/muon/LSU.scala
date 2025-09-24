package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util._

case class LoadStoreUnitParams (
    val numLsuLanes: Int = 16, // width of downstream memory interface; width of execute / writeback fixed to equal # of lanes
    val numLdqEntries: Int = 4, // TODO: should these be separately parameterizable for shared / global?
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
    val fence = Value

    val isLoad = (x: MemOp.Type) => x.isOneOf(loadByte, loadHalf, loadWord)
    val isStore = (x: MemOp.Type) => x.isOneOf(storeByte, storeHalf, storeWord)
    val isAtomic = (x: MemOp.Type) => x.isOneOf(amoSwap, amoAdd, amoAnd, amoOr, amoXor, amoMax, amoMin)
    val isFence = (x: MemOp.Type) => x.isOneOf(fence)
}

// Load and Store Queues must allocate slots in program order and produce an index for the allocated slot.
// This index must be held in reservation station and given to LSU when a memory instruction issues from RS
// (possibly out-of-order!) in order to handle hazards through memory. 
class LsuQueueReservationInterface(implicit p: MuonCoreParams) extends Bundle {
    val requestValid = Input(Bool())
    val addressSpace = Input(AddressSpace())
    val op = Input(MemOp())
    val warpId = Input(UInt(p.warpIdWidth.W))
    
    val outputValid = Output(Bool())
    val queueIndex = Output(UInt(p.lsu.queueIndexBits.W))
}

// 
class LsuQueueRequest(implicit p: MuonCoreParams) extends Bundle {

}

class LoadStoreQueue(implicit p: MuonCoreParams) extends Module {
    val io = IO(new Bundle {
        val lsuQueueReservationInterface = new LsuQueueReservationInterface
        
        val queuesEmpty = Output(Bool())
    })

    // helper functions for circular fifo indices
    val msb = (x: UInt) => x(x.getWidth - 1)
    val wrapBit = msb
    val idxBits = (x: UInt) => x(x.getWidth - 2, 0)

    // per-warp Circular FIFO parameterized to be a load queue or store queue
    class PerWarpQueue(
        loadQueue: Boolean
    ) extends Module {
        // parameterization as load queue or store queue
        val entries            = if (loadQueue) { p.lsu.numLdqEntries    } else { p.lsu.numStqEntries    }
        val circIndexBits      = if (loadQueue) { p.lsu.ldqCircIndexBits } else { p.lsu.stqCircIndexBits }
        val otherCircIndexBits = if (loadQueue) { p.lsu.stqCircIndexBits } else { p.lsu.ldqCircIndexBits }
        val indexBits          = if (loadQueue) { p.lsu.ldqIndexBits     } else { p.lsu.stqIndexBits     }

        val io = IO(new Bundle {
            val myHead = Output(UInt(circIndexBits.W))
            val myTail = Output(UInt(circIndexBits.W))

            // pointers from "other" queue for hazard tracking
            val otherHead = Input(UInt(otherCircIndexBits.W))
            val otherTail = Input(UInt(otherCircIndexBits.W))

            val full = Output(Bool())
            val empty = Output(Bool())

            val enqueue = Input(Bool())
            val op = Input(MemOp())

            // updates from reservation station and responses to requests issued from this queue
            val receivedOperands = Flipped(Valid(UInt(indexBits.W)))
            val receivedMemResponse = Flipped(Valid(UInt(indexBits.W)))

            // interface to issue request from this queue
            val requestIndex = Decoupled(UInt(indexBits.W))
        })

        val head = RegInit(0.U(circIndexBits.W))
        val tail = RegInit(0.U(circIndexBits.W))

        val full = (wrapBit(head) =/= wrapBit(tail)) && (idxBits(head) === idxBits(tail))
        val empty = (head === tail)

        io.myHead := head
        io.myTail := tail
        io.empty := empty
        io.full := full

        // Per-entry bookkeeping
        // - valid: entry allocated and not yet freed
        // - op: memory operation of this entry
        // - otherQueueTail: snapshot of "other" queue tail at allocation (for dependency checks)
        // - operandsReady: whether RS operands have arrived
        // - issued: whether a memory request was already sent
        val valid = RegInit(VecInit.fill(entries)(false.B))
        val op = Reg(Vec(entries, MemOp()))
        val otherQueueTailInit = VecInit.fill(entries)(0.U(otherCircIndexBits.W))
        val otherQueueTail = RegInit(otherQueueTailInit)
        val operandsReady = RegInit(VecInit.fill(entries)(false.B))
        val issued = RegInit(VecInit.fill(entries)(false.B))

        // allocation logic
        when (io.enqueue) {
            assert(!full, "overflow of per-warp queue")
            
            valid(idxBits(tail)) := true.B
            op(idxBits(tail)) := io.op
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
            // loads can only issue once all older stores have retired, but consecutive loads can be reordered
            val readyLoads = Wire(Vec(entries, Bool()))
            for (i <- 0 until entries) {
                val olderStoresRetired = msb(io.otherHead - otherQueueTail(i)) 
                readyLoads(i) := valid(i) && operandsReady(i) && olderStoresRetired && !issued(i)
            }

            // issue first ready load
            val anyLoadReady = readyLoads.asUInt.orR
            val readyLoadIndex = PriorityEncoder(readyLoads)

            io.requestIndex.valid := anyLoadReady
            io.requestIndex.bits := readyLoadIndex

            when (io.requestIndex.fire) {
                issued(readyLoadIndex) := true.B
            }
        }
        else {
            // Stores always issued in order, and must issue after older loads retired to avoid
            // WAR hazards through memory
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

        // deallocate once memory response received
        when (io.receivedMemResponse.valid) {
            valid(io.receivedMemResponse.bits) := false.B
        }

        // update head
        // TODO: should this be faster? e.g. skip ahead multiple entries
        when (!empty && !valid(idxBits(head))) {
            head := head + 1.U
        }
    }

    class PerWarpLoadQueue extends PerWarpQueue(loadQueue = true)
    class PerWarpStoreQueue extends PerWarpQueue(loadQueue = false)
    
    val warpEmptys = Wire(Vec(p.numWarps, Bool()))
    val allEmpty = warpEmptys.andR
    io.queuesEmpty := allEmpty

    val outputValids = Wire(Vec(p.numWarps, Bool()))
    val queueIndexes = Wire(Vec(p.numWarps, UInt(p.lsu.queueIndexBits.W)))
    io.lsuQueueReservationInterface.outputValid := outputValids(io.lsuQueueReservationInterface.warpId)
    io.lsuQueueReservationInterface.queueIndex := queueIndexes(io.lsuQueueReservationInterface.warpId)

    for (warp <- 0 until p.numWarps) {
        // instantiate queues, connect heads/tails
        val globalLoadQueue = Module(new PerWarpLoadQueue)
        val globalStoreQueue = Module(new PerWarpStoreQueue)

        val shmemLoadQueue = Module(new PerWarpLoadQueue)
        val shmemStoreQueue = Module(new PerWarpStoreQueue)

        globalLoadQueue.io.otherHead := globalStoreQueue.io.myHead
        globalLoadQueue.io.otherTail := globalStoreQueue.io.myTail
        globalStoreQueue.io.otherHead := globalLoadQueue.io.myHead
        globalStoreQueue.io.otherTail := globalLoadQueue.io.myTail

        shmemLoadQueue.io.otherHead := shmemStoreQueue.io.myHead
        shmemLoadQueue.io.otherTail := shmemStoreQueue.io.myTail
        shmemStoreQueue.io.otherHead := shmemLoadQueue.io.myHead
        shmemStoreQueue.io.otherTail := shmemLoadQueue.io.myTail

        // route reservation request / response
        def lsuQueueReservation = io.lsuQueueReservationInterface
        val queueFull = Wire(Bool())
        val queueIndex = Wire(UInt(p.lsu.queueIndexBits.W))

        val thisWarp = lsuQueueReservation.warpId === warp.U
        val globalMemory = lsuQueueReservation.addressSpace === AddressSpace.globalMemory
        val sharedMemory = lsuQueueReservation.addressSpace === AddressSpace.sharedMemory
        val isLoad = MemOp.isLoad(lsuQueueReservation.op)
        val isStore = MemOp.isStore(lsuQueueReservation.op)
        val isAtomic = MemOp.isAtomic(lsuQueueReservation.op)
        val isFence = MemOp.isFence(lsuQueueReservation.op)
        val reqValid = lsuQueueReservation.requestValid

        // atomics and fences both treated like stores, 
        // since current LDQ/STQ design already ensures that stores have aq and rl semantics
        val isSAF = isStore || isAtomic || isFence
        
        outputValids(warp) := MuxCase(false.B, Seq(
            (globalMemory && isLoad) -> !globalLoadQueue.io.full,
            (globalMemory && isSAF) -> !globalStoreQueue.io.full,
            (sharedMemory && isLoad) -> !shmemLoadQueue.io.full,
            (sharedMemory && isSAF) -> !shmemStoreQueue.io.full
        ))

        queueIndexes(warp) := MuxCase(DontCare, Seq(
            (globalMemory && isLoad) -> idxBits(globalLoadQueue.io.myTail),
            (globalMemory && isSAF) -> idxBits(globalStoreQueue.io.myTail),
            (sharedMemory && isLoad) -> idxBits(shmemLoadQueue.io.myTail),
            (sharedMemory && isSAF) -> idxBits(shmemStoreQueue.io.myTail)
        ))

        globalLoadQueue.io.enqueue := reqValid && thisWarp && globalMemory && isLoad && !globalLoadQueue.io.full
        globalStoreQueue.io.enqueue := reqValid && thisWarp && globalMemory && isSAF && !globalStoreQueue.io.full
        shmemLoadQueue.io.enqueue := reqValid && thisWarp && sharedMemory && isLoad && !shmemLoadQueue.io.full
        shmemStoreQueue.io.enqueue := reqValid && thisWarp && sharedMemory && isSAF && !shmemStoreQueue.io.full

        warpEmptys(warp) := {
            globalLoadQueue.io.empty &&
            globalStoreQueue.io.empty &&
            shmemLoadQueue.io.empty &&
            shmemStoreQueue.io.empty
        }
    }
}

// Execute interface
class LsuRequest(implicit p: MuonCoreParams) extends Bundle {
    val addressSpace = AddressSpace()
    val warpId = UInt(p.warpIdWidth.W)
    val op = MemOp()

    val tmask = Vec(p.numLanes, Bool())
    val address = Vec(p.numLanes, UInt(p.archLen.W))
    val data = Vec(p.numLanes, UInt(p.archLen.W))
}

// Writeback interface
class LsuResponse(implicit p: MuonCoreParams) extends Bundle {
    val warpId = UInt(p.warpIdWidth.W)
    val op = MemOp()

    val tmask = Vec(p.numLanes, Bool())
    val data = Vec(p.numLanes, UInt(p.archLen.W))
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

    // address / tmask of all warps, all queues share a single big SRAM
    val addressTmaskMemSize = p.numWarps * (p.lsu.numLdqEntries + p.lsu.numStqEntries + p.lsu.numLdqEntries + p.lsu.numStqEntries)
    val addressTmaskIndex = (warp: UInt, addressSpace: AddressSpace.Type, index: UInt) => {
        ???
    }
    val addressTmaskMem = SyncReadMem(addressTmaskMemSize, new Bundle { val addr = UInt(p.archLen.W); val tmask = UInt(p.numLanes.W) })
    
    // data for all warps' store queues share a single SRAM
    // data for all warps' load queues share a single SRAM
    // separation breaks contention between (responses to loads / store data from RS), which are both writes to SRAM
    // and (load data to writeback / store data to memory requests), which are both reads from SRAM

    val storeDataMemSize = ???
    val storeDataMem = SyncReadMem(storeDataMemSize, Vec(p.numLanes, UInt(p.archLen.W)))

    val loadDataMemSize = ???
    val loadDataMem = SyncReadMem(loadDataMemSize, Vec(p.numLanes, UInt(p.archLen.W)))
}
