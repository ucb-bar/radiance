package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util._
import radiance.muon.AddressSpaceCfg._

case class LoadStoreUnitParams(
    val numLsuLanes: Int = 16, // width of downstream memory interface and writeback; width of execute fixed to # of lanes
    
    val numGlobalLdqEntries: Int = 8, // limited to 8 decoded global load insts per warp
    val numGlobalStqEntries: Int = 4, // limited to 4 decoded global store insts per warp
    val numSharedLdqEntries: Int = 4, // limited to 4 decoded shared load insts per warp
    val numSharedStqEntries: Int = 2, // limited to 2 decoded shared store insts per warp

    val loadDataEntries: Int = 16, // limited to 16 in-flight / waiting to writeback load requests
    val storeDataEntries: Int = 8, // limited to 8 unissued store requests
    val addressEntries: Int = 16,  // limited to 16 unissued memory requests
) {
    val globalLdqIndexBits = log2Up(numGlobalLdqEntries)
    val globalStqIndexBits = log2Up(numGlobalStqEntries)
    val globalLdqCircIndexBits = globalLdqIndexBits + 1
    val globalStqCircIndexBits = globalStqIndexBits + 1

    val sharedLdqIndexBits = log2Up(numSharedLdqEntries)
    val sharedStqIndexBits = log2Up(numSharedStqEntries)
    val sharedLdqCircIndexBits = sharedLdqIndexBits + 1
    val sharedStqCircIndexBits = sharedStqIndexBits + 1
    
    val queueIndexBits = Seq(globalLdqIndexBits, globalStqIndexBits, sharedLdqIndexBits, sharedStqIndexBits).reduce(math.max)

    val loadDataIdxBits = log2Up(loadDataEntries)
    val storeDataIdxBits = log2Up(storeDataEntries)
    val addressIdxBits = log2Up(addressEntries)
}

class LoadStoreUnitDerivedParams(
    p: MuonCoreParams
) {
    require(p.numLanes % p.lsu.numLsuLanes == 0, "numLsuLanes must divide numLanes")

    val multiCycleWriteback = p.numLanes > p.lsu.numLsuLanes
    val numPackets = p.numLanes / p.lsu.numLsuLanes
    val packetBits = log2Up(numPackets)

    // "request tag"
    val sourceIdBits = LsuQueueToken.width(p) + packetBits
}

// Chisel type
object AddressSpace extends ChiselEnum {
    val globalMemory = Value
    val sharedMemory = Value
}

// Elaboration-time Scala type
sealed trait AddressSpaceCfg {
    def toChisel: AddressSpace.Type
}

object AddressSpaceCfg {
  case object Global extends AddressSpaceCfg {
    override def toChisel: AddressSpace.Type = AddressSpace.globalMemory
  }
  case object Shared extends AddressSpaceCfg {
    override def toChisel: AddressSpace.Type = AddressSpace.sharedMemory
  }
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

// Uniquely identifies an entry in load/store queues (warpId, addressSpace, ldq, index)
// Used as request tag into downstream memory interfaces
class LsuQueueToken(implicit p: MuonCoreParams) extends Bundle {
    val warpId = UInt(p.warpIdBits.W)
    val addressSpace = AddressSpace()
    val ldq = Bool()
    val index = UInt(p.lsu.queueIndexBits.W)
}

object LsuQueueToken {
    // a little sus
    def width(implicit p: MuonCoreParams): Int = {
        (new LsuQueueToken).asUInt.getWidth
    }
}

// Load and Store Queues must allocate slots in program order and produces a token for the allocated slot.
// This token must be held in reservation station and given to LSU when a memory instruction issues from RS
// (possibly out-of-order!) in order to handle hazards through memory.
// We expose a separate interface per warp to simplify checking for structural hazard on queue entries from per-warp IBUF
// Even if IBUF looks past its head to issue to RS, it still needs to ensure in-order reservations occur
class LsuQueueReservation(implicit p: MuonCoreParams) extends Bundle {
    val addressSpace = AddressSpace()
    val op = MemOp()

    val addressIdx = UInt(p.lsu.addressIdxBits.W)
    val storeDataIdx = UInt(p.lsu.storeDataIdxBits.W)

    // note: `token` and ready signal (fullness of queue) are 
    // combinationally coupled to addressSpace / op
    val token = Flipped(new LsuQueueToken)
}

// Once operand collector / forwarding network has finished, RS issues instruction to LSU,
// LSU informs Load and Store queues to indicate that memory request has received its operands.
// It must respond with addressIdx and storeDataIdx.
class LsuQueueOperandUpdate(implicit p: MuonCoreParams) extends LsuQueueToken {
    val pReg = UInt(p.pRegBits.W)

    val addressIdx = Flipped(UInt(p.lsu.addressIdxBits.W))
    val storeDataIdx = Flipped(UInt(p.lsu.storeDataIdxBits.W))
}

// Each load and store queue attempts to issue requests back to LSU in a correct order once 
// operands for that request are received and hazards resolved.
// Global memory, shared memory requests are separately arbitrated, creating two request
// pipes back to LSU.
// Deallocation of address / store data, Allocation for load data entry both occur here
class LsuQueueRequest(implicit p: MuonCoreParams) extends LsuQueueToken {
    val op = MemOp()

    val addressIdx = UInt(p.lsu.addressIdxBits.W)
    val storeDataIdx = UInt(p.lsu.storeDataIdxBits.W)

    val loadDataIdx = Flipped(UInt(p.lsu.loadDataIdxBits.W))
}

// Once memory subsystem responds with load data (or store ack), LSU notifies Load and Store queues
// so it can either reclaim the entry immediately (stores) or start driving writebacks
class LsuQueueMemResponse(implicit p: MuonCoreParams) extends LsuQueueToken

// This interface allows for an optimization for our shared memory design, which is guaranteed to not
// reorder requests. As such, we can advance head without waiting for load data (or store ack)
class LsuQueueMemUpdate(implicit p: MuonCoreParams) extends LsuQueueToken

// Finally, load store queue drives writeback for load / atomic operations. 
// Deallocation of load data entries occurs here
// We don't send anything down writeback path for fences / stores
// TODO: maybe want something to allow for instrumentation of fences / stores
class LsuQueueWritebackRequest(implicit p: MuonCoreParams) extends LsuQueueToken {
    val loadDataIdx = UInt(p.lsu.loadDataIdxBits.W)
}

class LoadStoreQueue(implicit p: MuonCoreParams) extends Module {
    val io = IO(new Bundle {
        val queueReservations = Vec(p.numWarps, Flipped(Decoupled(new LsuQueueReservation)))
        
        val globalReq = Decoupled(new LsuQueueRequest)
        val shmemReq = Decoupled(new LsuQueueRequest)

        val receivedOperands = Flipped(Valid(new LsuQueueOperandUpdate))
        val receivedMemUpdate = Flipped(Valid(new LsuQueueMemUpdate))
        val receivedMemResponse = Flipped(Valid(new LsuQueueMemResponse))
        
        val writebackReq = Decoupled(new LsuQueueWritebackRequest) 

        // used to flush LSU
        val queuesEmpty = Output(Bool())
    })

    // helper functions for circular fifo indices
    val msb = (x: UInt) => x(x.getWidth - 1)
    val wrapBit = msb
    val idxBits = (x: UInt) => x(x.getWidth - 2, 0)

    // per-warp Circular FIFO parameterized to be a load queue or store queue
    class PerWarpQueue(
        warpId: Int,
        addressSpace: AddressSpaceCfg,
        loadQueue: Boolean
    ) extends Module {
        // parameterization as load queue or store queue, global or shared memory
        val (entries, circIndexBits, otherCircIndexBits, indexBits) = addressSpace match {
            case Global => {
                val entries            = if (loadQueue) { p.lsu.numGlobalLdqEntries    } else { p.lsu.numGlobalStqEntries    }
                val circIndexBits      = if (loadQueue) { p.lsu.globalLdqCircIndexBits } else { p.lsu.globalStqCircIndexBits }
                val otherCircIndexBits = if (loadQueue) { p.lsu.globalStqCircIndexBits } else { p.lsu.globalLdqCircIndexBits }
                val indexBits          = if (loadQueue) { p.lsu.globalLdqIndexBits     } else { p.lsu.globalStqIndexBits     }

                (entries, circIndexBits, otherCircIndexBits, indexBits)
            }
            case Shared => {
                val entries            = if (loadQueue) { p.lsu.numSharedLdqEntries    } else { p.lsu.numSharedStqEntries    }
                val circIndexBits      = if (loadQueue) { p.lsu.sharedLdqCircIndexBits } else { p.lsu.sharedStqCircIndexBits }
                val otherCircIndexBits = if (loadQueue) { p.lsu.sharedStqCircIndexBits } else { p.lsu.sharedLdqCircIndexBits }
                val indexBits          = if (loadQueue) { p.lsu.sharedLdqIndexBits     } else { p.lsu.sharedStqIndexBits     }

                (entries, circIndexBits, otherCircIndexBits, indexBits)
            }
        }

        def makeToken(idx: UInt): LsuQueueToken = {
            val token = Wire(new LsuQueueToken)
            token.warpId := warpId.U
            token.addressSpace := addressSpace.toChisel
            token.ldq := loadQueue.B
            token.index := idx.padTo(p.lsu.queueIndexBits)

            token
        }

        val io = IO(new Bundle {
            val myHead = Output(UInt(circIndexBits.W))
            val myTail = Output(UInt(circIndexBits.W))

            // pointers from "other" queue for hazard tracking
            val otherHead = Input(UInt(otherCircIndexBits.W))
            val otherTail = Input(UInt(otherCircIndexBits.W))

            val full = Output(Bool())
            val empty = Output(Bool())
            
            // enqueue interface
            val enqueue = Input(Bool())
            val op = Input(MemOp())
            val addressIdx = Input(UInt(p.lsu.addressIdxBits.W))
            val storeDataIdx = Input(UInt(p.lsu.storeDataIdxBits.W))

            val receivedOperands = Flipped(Valid(new LsuQueueOperandUpdate))

            // interface to issue request from this queue
            val req = Decoupled(new LsuQueueRequest)
            
            val receivedMemResponse = Flipped(Valid(new LsuQueueMemResponse))
            val receivedMemUpdate = Flipped(Valid(new LsuQueueMemUpdate))

            val writebackReq = Decoupled(new LsuQueueWritebackRequest)
        })

        val physicalHead = RegInit(0.U(circIndexBits.W))
        val logicalHead = RegInit(0.U(circIndexBits.W))
        val tail = RegInit(0.U(circIndexBits.W))

        val full = (wrapBit(physicalHead) =/= wrapBit(tail)) && (idxBits(physicalHead) === idxBits(tail))
        val empty = (physicalHead === tail)

        io.myHead := logicalHead
        io.myTail := tail
        io.empty := empty
        io.full := full

        // Per-entry bookkeeping
        // - valid: entry allocated and not yet freed
        // - op: memory operation of this entry
        // - otherQueueTail: snapshot of "other" queue tail at allocation (for dependency checks)
        // - operandsReady: whether RS operands have arrived
        // - issued: whether a memory request was already sent
        // - addressTmaskIndex: SRAM index for address/tmask data
        // - storeDataIndex: SRAM index for store data (STQ only)
        // - loadDataIndex: SRAM index for load data (LDQ and STQ, since latter has to deal with atomics also)
        // - done: either we received a mem update or mem response; dependent memory instructions are free to go
        //   (but we can't deallocate entry yet; need loadDataIndex / packet to do writeback)
        // - writeback: we received a mem response and can begin to write back
        // - packet: "packet" index for multi-cycle writeback when numLsuLanes < numLanes
        val valid              = RegInit(VecInit.fill(entries)(false.B))
        val op                 = RegInit(VecInit.fill(entries)(MemOp.loadWord))
        val otherQueueTail     = RegInit(VecInit.fill(entries)(0.U(otherCircIndexBits.W)))
        val operandsReady      = RegInit(VecInit.fill(entries)(false.B))
        val issued             = RegInit(VecInit.fill(entries)(false.B))
        val addressIdx         = RegInit(VecInit.fill(entries)(0.U(p.lsu.addressIdxBits.W)))
        val storeDataIdx       = RegInit(VecInit.fill(entries)(0.U(p.lsu.storeDataIdxBits.W)))
        val loadDataIdx        = RegInit(VecInit.fill(entries)(0.U(p.lsu.loadDataIdxBits.W)))
        val done               = RegInit(VecInit.fill(entries)(false.B))
        val writeback          = RegInit(VecInit.fill(entries)(false.B))
        
        // allocation logic
        when (io.enqueue) {
            assert(!full, "overflow of per-warp queue")
            
            valid(idxBits(tail)) := true.B
            op(idxBits(tail)) := io.op
            otherQueueTail(idxBits(tail)) := io.otherTail
            operandsReady(idxBits(tail)) := false.B
            issued(idxBits(tail)) := false.B

            addressIdx(idxBits(tail)) := io.addressIdx
            if (!loadQueue) {
                storeDataIdx(idxBits(tail)) := io.storeDataIdx
            }
            done(idxBits(tail)) := false.B
            
            tail := tail + 1.U
        }

        // mark when operands received from reservation station and respond with
        // addressIdx and storeDataIdx
        io.receivedOperands.bits.addressIdx := addressIdx(io.receivedOperands.bits.index)
        io.receivedOperands.bits.storeDataIdx := (if (loadQueue) { DontCare } else { storeDataIdx(io.receivedOperands.bits.index) })
        when (io.receivedOperands.valid) {
            assert(valid(io.receivedOperands.bits.index), "invalid index from reservation station")

            operandsReady(io.receivedOperands.bits.index) := true.B
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

            io.req.valid := anyLoadReady

            io.req.bits := makeToken(readyLoadIndex)

            io.req.bits.op := op(readyLoadIndex)
            io.req.bits.addressIdx := addressIdx(readyLoadIndex)
            
            when (io.req.fire) {
                loadDataIdx(readyLoadIndex) := io.req.bits.loadDataIdx
                issued(readyLoadIndex) := true.B
            }
        }
        else {
            // Stores always issued in order, and must issue after older loads retired to avoid
            // WAR hazards through memory
            val olderLoadsRetired = msb(io.otherHead - otherQueueTail(idxBits(logicalHead)))

            val readyStore = {
                valid(idxBits(logicalHead)) &&
                operandsReady(idxBits(logicalHead)) &&
                olderLoadsRetired &&
                !issued(idxBits(logicalHead))
            }
            val readyStoreIndex = idxBits(logicalHead)
            val readyStoreOp = op(readyStoreIndex)

            io.req.valid := readyStore
            io.req.bits := makeToken(readyStoreIndex)
            io.req.bits.addressIdx := addressIdx(readyStoreIndex)
            io.req.bits.storeDataIdx := storeDataIdx(readyStoreIndex)

            // fences are fake entries, and don't generate any downstream requests
            // instead, they immediately retire 
            when (MemOp.isFence(readyStoreOp)) {
                io.req.valid := false.B
                done(readyStoreIndex) := true.B
                valid(readyStoreIndex) := false.B
            }

            when (io.req.fire) {
                when (MemOp.isAtomic(readyStoreOp)) {
                    loadDataIdx(readyStoreIndex) := io.req.bits.loadDataIdx
                }
                issued(readyStoreIndex) := true.B
            }
        }

        // set done when receiving mem update or mem response, allow logical head to move forward
        when (io.receivedMemUpdate.valid) {
            done(io.receivedMemUpdate.bits.index) := true.B    
        }

        when (io.receivedMemResponse.valid) {
            done(io.receivedMemResponse.bits.index) := true.B
            
            if (loadQueue) {
                // every load needs to write back
                writeback(io.receivedMemResponse.bits.index) := true.B
            }
            else {
                // only atomics need to write back; others can retire
                val atomic = MemOp.isAtomic(op(io.receivedMemResponse.bits.index))
                writeback(io.receivedMemResponse.bits.index) := atomic

                when (!atomic) {
                    valid(io.receivedMemResponse.bits.index) := false.B
                }
            }
        }

        // drive writeback requests
        val writebackIdx = PriorityEncoder(writeback.asUInt)
        io.writebackReq.valid := writeback.orR
        io.writebackReq.bits := makeToken(writebackIdx)
        io.writebackReq.bits.loadDataIdx := loadDataIdx(writebackIdx)
        when (io.writebackReq.fire) {
            writeback(writebackIdx) := false.B
            valid(writebackIdx) := false.B
            
        }

        // update logical head
        // update physical head
        // TODO: optimize this to be faster (multiple entries? probably want at least same cycle updates)
        when (logicalHead =/= tail && done(idxBits(logicalHead))) {
            logicalHead := logicalHead + 1.U
        }
        
        when (!empty && !valid(idxBits(physicalHead))) {
            physicalHead := physicalHead + 1.U
        }
    }

    class PerWarpLoadQueue(warpId: Int, addressSpace: AddressSpaceCfg) 
        extends PerWarpQueue(warpId, addressSpace, loadQueue = true)
    class PerWarpStoreQueue(warpId: Int, addressSpace: AddressSpaceCfg) 
        extends PerWarpQueue(warpId, addressSpace, loadQueue = false)
    
    val warpEmptys = Wire(Vec(p.numWarps, Bool()))
    val allEmpty = warpEmptys.andR
    io.queuesEmpty := allEmpty

    // instantiate queues
    val globalLoadQueues = Seq.tabulate(p.numWarps)(w => Module(new PerWarpLoadQueue(w, AddressSpaceCfg.Global)))
    val globalStoreQueues = Seq.tabulate(p.numWarps)(w => Module(new PerWarpStoreQueue(w, AddressSpaceCfg.Global)))
    val shmemLoadQueues = Seq.tabulate(p.numWarps)(w => Module(new PerWarpLoadQueue(w, AddressSpaceCfg.Shared)))
    val shmemStoreQueues = Seq.tabulate(p.numWarps)(w => Module(new PerWarpStoreQueue(w, AddressSpaceCfg.Shared)))

    for (warp <- 0 until p.numWarps) {
        // connect heads/tails
        val globalLoadQueue = globalLoadQueues(warp)
        val globalStoreQueue = globalStoreQueues(warp)

        val shmemLoadQueue = shmemLoadQueues(warp)
        val shmemStoreQueue = shmemStoreQueues(warp)

        globalLoadQueue.io.otherHead := globalStoreQueue.io.myHead
        globalLoadQueue.io.otherTail := globalStoreQueue.io.myTail
        globalStoreQueue.io.otherHead := globalLoadQueue.io.myHead
        globalStoreQueue.io.otherTail := globalLoadQueue.io.myTail

        shmemLoadQueue.io.otherHead := shmemStoreQueue.io.myHead
        shmemLoadQueue.io.otherTail := shmemStoreQueue.io.myTail
        shmemStoreQueue.io.otherHead := shmemLoadQueue.io.myHead
        shmemStoreQueue.io.otherTail := shmemLoadQueue.io.myTail

        // route reservation request / response
        val lsuQueueReservation = io.queueReservations(warp)
        val queueIndex = Wire(UInt(p.lsu.queueIndexBits.W))

        val globalMemory = lsuQueueReservation.bits.addressSpace === AddressSpace.globalMemory
        val sharedMemory = lsuQueueReservation.bits.addressSpace === AddressSpace.sharedMemory
        val isLoad = MemOp.isLoad(lsuQueueReservation.bits.op)
        val isStore = MemOp.isStore(lsuQueueReservation.bits.op)
        val isAtomic = MemOp.isAtomic(lsuQueueReservation.bits.op)
        val isFence = MemOp.isFence(lsuQueueReservation.bits.op)
        val reqValid = lsuQueueReservation.valid

        // atomics and fences both treated like stores, 
        // since current LDQ/STQ design already ensures that stores have aq and rl semantics
        val isSAF = isStore || isAtomic || isFence
        
        lsuQueueReservation.ready := MuxCase(false.B, Seq(
            (globalMemory && isLoad) -> globalLoadQueue.io.full,
            (globalMemory && isSAF) -> globalStoreQueue.io.full,
            (sharedMemory && isLoad) -> shmemLoadQueue.io.full,
            (sharedMemory && isSAF) -> shmemStoreQueue.io.full
        ))

        val token = Wire(new LsuQueueToken)
        token.warpId := warp.U
        token.addressSpace := lsuQueueReservation.bits.addressSpace
        token.ldq := isLoad
        token.index := MuxCase(DontCare, Seq(
            (globalMemory && isLoad) -> idxBits(globalLoadQueue.io.myTail),
            (globalMemory && isSAF) -> idxBits(globalStoreQueue.io.myTail),
            (sharedMemory && isLoad) -> idxBits(shmemLoadQueue.io.myTail),
            (sharedMemory && isSAF) -> idxBits(shmemStoreQueue.io.myTail)
        ))
        lsuQueueReservation.bits.token := token

        globalLoadQueue.io.enqueue  := lsuQueueReservation.fire && globalMemory && isLoad
        globalStoreQueue.io.enqueue := lsuQueueReservation.fire && globalMemory && isSAF
        shmemLoadQueue.io.enqueue   := lsuQueueReservation.fire && sharedMemory && isLoad
        shmemStoreQueue.io.enqueue  := lsuQueueReservation.fire && sharedMemory && isSAF
        
        for (queue <- Seq(globalLoadQueue, globalStoreQueue, shmemLoadQueue, shmemStoreQueue)) {
            queue.io.addressIdx := lsuQueueReservation.bits.addressIdx
            queue.io.storeDataIdx := lsuQueueReservation.bits.storeDataIdx
        }

        // set flag if all queues for this warp are empty
        warpEmptys(warp) := {
            globalLoadQueue.io.empty &&
            globalStoreQueue.io.empty &&
            shmemLoadQueue.io.empty &&
            shmemStoreQueue.io.empty
        }
    }

    // arbitrate memory requests between queues. note that load queue / store queue of a given warp can never
    // both be trying to issue a request on same cycle
    
    // current arbitration: loads > stores, then lower warpId > higher warpId
    // TODO: better arbitration scheme?
    val nGlobalReqs = p.numWarps * 2 // 1 global load queue, 1 global store queue per warp 
    val globalReqArbiter = Module(new Arbiter(new LsuQueueRequest, nGlobalReqs))

    val nShmemReqs = p.numWarps * 2
    val shmemReqArbiter = Module(new Arbiter(new LsuQueueRequest, nShmemReqs))

    io.globalReq :<>= globalReqArbiter.io.out
    io.shmemReq :<>= shmemReqArbiter.io.out

    (globalReqArbiter.io.in zip (globalLoadQueues ++ globalStoreQueues)).foreach(x => {
        val (arbPort, queue) = x
        arbPort :<>= queue.io.req
    })

    (shmemReqArbiter.io.in zip (shmemLoadQueues ++ shmemStoreQueues)).foreach(x => {
        val (arbPort, queue) = x
        arbPort :<>= queue.io.req
    })
    
    // Route signals to per-warp queues and handle reverse direction
    for (warp <- 0 until p.numWarps) {
        val globalLoadQueue = globalLoadQueues(warp)
        val globalStoreQueue = globalStoreQueues(warp)
        val shmemLoadQueue = shmemLoadQueues(warp)
        val shmemStoreQueue = shmemStoreQueues(warp)
        
        def routeToken[T <: LsuQueueToken](
            token: LsuQueueToken, 
            valid: Bool, 
            signal: (PerWarpQueue) => T, 
            signalValid: (PerWarpQueue) => Bool
        ): Unit = {
            val thisWarp = token.warpId === warp.U
            val globalMemory = token.addressSpace === AddressSpace.globalMemory
            val sharedMemory = token.addressSpace === AddressSpace.sharedMemory
            val ldq = token.ldq

            signalValid(globalLoadQueue) := thisWarp && globalMemory && valid && ldq
            signal(globalLoadQueue) := token
            signalValid(globalStoreQueue) := thisWarp && globalMemory && valid && !ldq
            signal(globalStoreQueue) := token
            signalValid(shmemLoadQueue) := thisWarp && sharedMemory && valid && ldq
            signal(shmemLoadQueue) := token
            signalValid(shmemStoreQueue) := thisWarp && sharedMemory && valid && !ldq
            signal(shmemStoreQueue) := token
        }

        // Forward direction: route incoming signals to correct queue
        routeToken(io.receivedOperands.bits, io.receivedOperands.valid, q => q.io.receivedOperands.bits, q => q.io.receivedOperands.valid)
        routeToken(io.receivedMemUpdate.bits, io.receivedMemUpdate.valid, q => q.io.receivedMemUpdate.bits, q => q.io.receivedMemUpdate.valid)
        routeToken(io.receivedMemResponse.bits, io.receivedMemResponse.valid, q => q.io.receivedMemResponse.bits, q => q.io.receivedMemResponse.valid)
    }
    
    // Reverse direction: Only one queue should be responding at a time, so we can use priority encoding
    val allQueueIOs = VecInit((globalLoadQueues ++ globalStoreQueues ++ shmemLoadQueues ++ shmemStoreQueues).map(_.io))

    {
        val respondingQueues = (globalLoadQueues ++ globalStoreQueues ++ shmemLoadQueues ++ shmemStoreQueues).map(q => q.io.receivedOperands.valid)
        val responseIndex = PriorityEncoder(respondingQueues.asUInt)
        val responseQueue = allQueueIOs(responseIndex)
        io.receivedOperands.bits.addressIdx := responseQueue.receivedOperands.bits.addressIdx
        io.receivedOperands.bits.storeDataIdx := responseQueue.receivedOperands.bits.storeDataIdx
    }

    // arbitrate writeback request between queues
    // currently: shared loads > shared atomics > global loads > global atomics
    // TODO: better arbitration?
    val nWritebackReqs = p.numWarps * 2 // 1 global load queue, 1 global store queue per warp 
    val writebackArbiter = Module(new Arbiter(new LsuQueueWritebackRequest, nWritebackReqs))
    io.writebackReq :<>= writebackArbiter.io.out

    (writebackArbiter.io.in zip (shmemLoadQueues ++ shmemStoreQueues ++ globalLoadQueues ++ globalStoreQueues)).foreach(x => {
        val (arbPort, queue) = x
        arbPort :<>= queue.io.writebackReq
    })
}

// Forwards to per-warp LsuQueueReservation interface, following allocation
class LsuReservation(implicit p: MuonCoreParams) extends Bundle {
    val addressSpace = AddressSpace()
    val op = MemOp()

    // combinationally coupled to addressSpace / op
    val token = Flipped(new LsuQueueToken)
}

// Execute interface
class LsuRequest(implicit p: MuonCoreParams) extends Bundle {
    val token = new LsuQueueToken

    val tmask = Vec(p.numLanes, Bool())
    val address = Vec(p.numLanes, UInt(p.archLen.W))
    val imm = UInt(p.archLen.W)

    val destReg = UInt(p.pRegBits.W)
    val storeData = Vec(p.numLanes, UInt(p.archLen.W))
}

// Writeback interface to rest of core
class LsuResponse(implicit p: MuonCoreParams) extends Bundle {
    val warpId = UInt(p.warpIdBits.W)

    val packet = UInt(p.lsuDerived.packetBits.W)
    val tmask = Vec(p.numLanes, Bool())
    val destReg = UInt(p.pRegBits.W)
    val writebackData = Vec(p.lsu.numLsuLanes, UInt(p.archLen.W))
}

// Downstream memory interface
class LsuMemRequest(implicit p: MuonCoreParams) extends Bundle {
    val tag = UInt(p.lsuDerived.sourceIdBits.W)
    
    val op = MemOp()
    val address = Vec(p.lsu.numLsuLanes, UInt(p.archLen.W))
    val data = Vec(p.lsu.numLsuLanes, UInt(p.archLen.W))
    val tmask = Vec(p.lsu.numLsuLanes, Bool())
}

class LsuMemResponse(implicit p: MuonCoreParams) extends Bundle {
    val tag = UInt(p.lsuDerived.sourceIdBits.W)

    val data = Vec(p.lsu.numLsuLanes, UInt(p.archLen.W))
}

// free list allocator
class FreeListAllocator(entries: Int) extends Module {
    val io = IO(new Bundle {
        val allocate = Input(Bool())
        val deallocate = Input(Bool())
        val deallocateIndex = Input(UInt(log2Up(entries).W))
        
        val hasFree = Output(Bool())
        val allocatedIndex = Output(UInt(log2Up(entries).W))
        val allocationValid = Output(Bool())
    })
    
    val freeList = RegInit(VecInit.tabulate(entries)(i => i.U(log2Up(entries).W)))
    val freeHead = RegInit(0.U(log2Up(entries).W))
    val freeTail = RegInit((entries - 1).U(log2Up(entries).W))
    val allocated = RegInit(VecInit.fill(entries)(false.B))
    
    val hasFree = freeHead =/= freeTail
    val allocIndex = freeList(freeHead)
    
    io.hasFree := hasFree
    io.allocatedIndex := allocIndex
    io.allocationValid := hasFree && io.allocate
    
    // Allocation
    when (io.allocate && hasFree) {
        allocated(allocIndex) := true.B
        freeHead := freeHead + 1.U
    }
    
    // Deallocation
    when (io.deallocate) {
        allocated(io.deallocateIndex) := false.B
        freeList(freeTail) := io.deallocateIndex
        freeTail := freeTail + 1.U
    }
}

object Utils {
    def selectPacket[T <: Data](vec: Vec[T], packet: UInt)(implicit p: MuonCoreParams): Vec[T] = {
        require(vec.length == p.numLanes, "Vec length must be equal to numLanes")
        val out = Wire(Vec(p.lsu.numLsuLanes, vec.head.cloneType))
        for (i <- 0 until p.lsu.numLsuLanes) {
            out(i) := MuxLookup(packet, 0.U.asTypeOf(vec.head))(
                (0 until p.lsuDerived.numPackets).map(c => c.U -> vec(c * p.lsu.numLsuLanes + i))
            )
        }
        out
    }

    def validArb[T <: Data](gen: T, n: Int): (Vec[ValidIO[T]], Vec[Bool], T, Bool) = {
        val arb = Module(new Arbiter(gen, n))
        val in = Wire(Vec(n, chiselTypeOf(Valid(gen))))
        for (i <- 0 until n) {
            arb.io.in(i).valid := in(i).valid
            arb.io.in(i).bits := in(i).bits
        }
        arb.io.out.ready := true.B

        (in, VecInit(arb.io.chosen.asBools), arb.io.out.bits, arb.io.out.valid)
    }
}

class LoadStoreUnit(muonParams: MuonCoreParams) extends Module {
    implicit val p = muonParams

    val io = IO(new Bundle {
        val coreReq = Flipped(Decoupled(new LsuRequest))
        val coreReservations = Vec(p.numWarps, Flipped(Decoupled(new LsuQueueReservation)))
        val coreResp = Decoupled(new LsuResponse)

        val globalMemReq = Decoupled(new LsuMemRequest)
        val globalMemResp = Flipped(Decoupled(new LsuMemResponse))

        val shmemReq = Decoupled(new LsuMemRequest)
        val shmemResp = Flipped(Decoupled(new LsuMemResponse))

        val empty = Output(Bool())
    })

    // instantiate lsu queues
    val loadStoreQueues = Module(new LoadStoreQueue)
    io.empty := loadStoreQueues.io.queuesEmpty

    // Dynamic allocation system using free list allocators
    // addressTmask and storeData indices are allocated at queue entry reservation time
    // This is necessary to prevent deadlock
    // addressTmask - if allocated at operand arrival time, then this can cause deadlock
    //  - e.g. every load queue blocked on a store, but all load operands arrive first 
    //  and fill up addressTmask SRAM
    // storeData - if allocated at operand arrival time, this can cause deadlock
    //  - e.g. entries beyond head have operands arrive first, fill up storeData SRAM
    // loadData - can be allocated at memory response time without causing deadlock,
    //  but I assume we cannot apply backpressure on global memory path, meaning you would need
    //  to replay
    //  - more conservative option is allocation at mem request issue time
    val addressTmaskAllocator = Module(new FreeListAllocator(p.lsu.addressEntries))
    val storeDataAllocator = Module(new FreeListAllocator(p.lsu.storeDataEntries))
    val loadDataAllocator = Module(new FreeListAllocator(p.lsu.loadDataEntries))

    // pRegTmask is allocated statically, 1 for every entry in every warp.
    def tokenToPRegTmaskIndex = (token: LsuQueueToken) => {
        val warpStride = p.lsu.numGlobalLdqEntries + p.lsu.numGlobalStqEntries + p.lsu.numSharedLdqEntries + p.lsu.numSharedStqEntries
        val offset = MuxCase(0.U, Seq(
            (token.addressSpace === AddressSpace.globalMemory && token.ldq) -> 0.U,
            (token.addressSpace === AddressSpace.globalMemory && !token.ldq) -> (p.lsu.numGlobalLdqEntries).U,
            (token.addressSpace === AddressSpace.sharedMemory && token.ldq) -> (p.lsu.numGlobalLdqEntries + p.lsu.numGlobalStqEntries).U,
            (token.addressSpace === AddressSpace.sharedMemory && !token.ldq) -> (p.lsu.numGlobalLdqEntries + p.lsu.numGlobalStqEntries + p.lsu.numSharedLdqEntries).U
        ))

        token.warpId * warpStride.U + offset + token.index
    }
    
    // SRAMs
    class AddressTmask extends Bundle {
        val address = Vec(p.numLanes, UInt(p.archLen.W))
        val tmask = Vec(p.numLanes, Bool())
    }

    class PRegTmask extends Bundle {
        val tmask = Vec(p.numLanes, Bool())
        val destReg = UInt(p.pRegBits.W)
    }

    val addressTmaskMem = SyncReadMem(p.lsu.addressEntries, new AddressTmask)
    val storeDataMem = SyncReadMem(p.lsu.storeDataEntries, Vec(p.numLanes, UInt(p.archLen.W)))
    val loadDataMem = SyncReadMem(p.lsu.loadDataEntries * p.lsuDerived.numPackets, Vec(p.lsu.numLsuLanes, UInt(p.archLen.W)))
    
    val totalQueueEntries = p.numWarps * (p.lsu.numGlobalLdqEntries + p.lsu.numGlobalStqEntries + p.lsu.numSharedLdqEntries + p.lsu.numSharedStqEntries)
    val pRegTmaskMem = SyncReadMem(totalQueueEntries, new PRegTmask)

    // arbitrate between reservations, injecting allocated address SRAM index
    // and allocated store data index (for stores / atomics)
    // TODO: better arbitration?
    addressTmaskAllocator.io.allocate := false.B
    storeDataAllocator.io.allocate := false.B

    for (warp <- 0 until p.numWarps) {
        val queueReservation = loadStoreQueues.io.queueReservations(warp)
        val coreReservation = io.coreReservations(warp)

        queueReservation.bits.addressSpace := coreReservation.bits.addressSpace
        queueReservation.bits.op := coreReservation.bits.op
    }

    val coreReservationValids = Cat(io.coreReservations.map(r => r.valid))
    val queueReservationReadys = Cat(loadStoreQueues.io.queueReservations.map(r => r.ready))
    val reservationValidOH = PriorityEncoderOH(coreReservationValids & queueReservationReadys)
    
    for (warp <- 0 until p.numWarps) {
        val queueReservation = loadStoreQueues.io.queueReservations(warp)
        val coreReservation = io.coreReservations(warp)
        val reservationValid = reservationValidOH(warp)
        when (reservationValid) {
            // fences don't need a slot in store data, despite being kept in store queue
            when (MemOp.isFence(coreReservation.bits.op)) {
                queueReservation.valid := true.B
                queueReservation.bits.addressIdx := DontCare
                queueReservation.bits.storeDataIdx := DontCare
            }.elsewhen(MemOp.isStore(coreReservation.bits.op) || MemOp.isAtomic(coreReservation.bits.op)) {
                addressTmaskAllocator.io.allocate := true.B
                storeDataAllocator.io.allocate := true.B
                
                queueReservation.valid := addressTmaskAllocator.io.allocationValid && storeDataAllocator.io.allocationValid
                queueReservation.bits.addressIdx := addressTmaskAllocator.io.allocatedIndex
                queueReservation.bits.storeDataIdx := storeDataAllocator.io.allocatedIndex
            }.otherwise {
                addressTmaskAllocator.io.allocate := true.B

                queueReservation.valid := addressTmaskAllocator.io.allocationValid
                queueReservation.bits.addressIdx := addressTmaskAllocator.io.allocatedIndex
                queueReservation.bits.storeDataIdx := DontCare
            }
        }.otherwise {
            queueReservation.valid := false.B
            queueReservation.bits.addressIdx := DontCare
            queueReservation.bits.storeDataIdx := DontCare
        }
    }

    // -- Accept operands from reservation station --
    
    // by design, we are always ready to accept operands from reservation station 
    io.coreReq.ready := true.B
    val queueReceivedOperands = loadStoreQueues.io.receivedOperands
    queueReceivedOperands.valid := io.coreReq.valid
    queueReceivedOperands.bits := io.coreReq.bits.token
    when (io.coreReq.fire) {
        queueReceivedOperands.bits.pReg := io.coreReq.bits.destReg
        val addressTmask = Wire(new AddressTmask)
        
        // address generation
        val imm = io.coreReq.bits.imm
        val address = VecInit(io.coreReq.bits.address.map(_ + imm))
        addressTmask.address := address
        addressTmask.tmask := io.coreReq.bits.tmask

        addressTmaskMem.write(queueReceivedOperands.bits.addressIdx, addressTmask)
        storeDataMem.write(queueReceivedOperands.bits.storeDataIdx, io.coreReq.bits.storeData)
    }

    // -- Generate downstream memory requests -- 

    // arbitrate addressTmask / storeData read ports between global and shared mem requests
    val (addressTmaskReadIdxArbIn, addressTmaskReadIdxArbChosen, addressTmaskReadIdxArbOut, addressTmaskReadIdxArbValid) = Utils.validArb(UInt(p.lsu.addressIdxBits.W), 2)
    val (storeDataReadIdxArbIn, storeDataReadIdxArbChosen, storeDataReadIdxArbOut, storeDataReadIdxArbValid) = Utils.validArb(UInt(p.lsu.storeDataIdxBits.W), 2)
    val addressTmask = storeDataMem.read(storeDataReadIdxArbOut, addressTmaskReadIdxArbValid)
    val storeData = storeDataMem.read(storeDataReadIdxArbOut, storeDataReadIdxArbValid)

    class MemRequestGen(bufferFlop: Boolean) extends Module {
        val io = IO(new Bundle {
            val queueRequest = Flipped(Decoupled(new LsuQueueRequest))
            val memRequest = Decoupled(new LsuMemRequest)

            val addressTmaskRead = Decoupled(UInt(p.lsu.addressIdxBits.W))
            val addressTmask = Input(new AddressTmask)

            val storeDataRead = Decoupled(UInt(p.lsu.storeDataIdxBits.W))
            val storeData = Input(Vec(p.numLanes, UInt(p.archLen.W)))
        })

        if (bufferFlop) {
            val valid = RegInit(false.B)
            val packet = RegInit(0.U(p.lsuDerived.packetBits.W))
            val finalPacket = (packet === (p.lsuDerived.numPackets - 1).U)
            
            val op = RegInit(MemOp.loadWord)
            val token = RegInit(0.U.asTypeOf(new LsuQueueToken))

            io.addressTmaskRead.bits := io.queueRequest.bits.addressIdx
            io.addressTmaskRead.valid := io.queueRequest.valid

            io.storeDataRead.bits := io.queueRequest.bits.storeDataIdx
            io.storeDataRead.valid := io.queueRequest.valid

            // either there is no request currently, or its about to finish
            // in either case, we wait until we have grant
            io.queueRequest.ready := (!valid || (finalPacket && io.memRequest.fire)) && io.addressTmaskRead.fire
            
            when (io.memRequest.fire) {
                packet := packet + 1.U
            }

            when (io.queueRequest.fire) {
                valid := true.B
                packet := 0.U
                op := io.queueRequest.bits.op
                token := io.queueRequest.bits
            }

            val queueRequestFirePrev = RegNext(io.queueRequest.fire, false.B)
            val addressTmask = RegInit(0.U.asTypeOf(new AddressTmask))
            val storeData = RegInit(VecInit.fill(p.numLanes)(0.U(p.archLen.W)))
            when (queueRequestFirePrev) {
                addressTmask := io.addressTmask
                storeData := io.storeData

                io.memRequest.bits.address := Utils.selectPacket(io.addressTmask.address, packet)
                io.memRequest.bits.data := Utils.selectPacket(io.storeData, packet)
                io.memRequest.bits.tmask := Utils.selectPacket(io.addressTmask.tmask, packet)
            }.otherwise {
                io.memRequest.bits.address := Utils.selectPacket(addressTmask.address, packet)
                io.memRequest.bits.data := Utils.selectPacket(storeData, packet)
                io.memRequest.bits.tmask := Utils.selectPacket(addressTmask.tmask, packet)
            }

            io.memRequest.valid := valid 
            io.memRequest.bits.op := op
            io.memRequest.bits.tag := Cat(token.asUInt, packet)            
        }
        else {
            ???
        }
    }

    // TODO: continually read from SRAM, or store to intermediate flop?
    val globalReqGen = Module(new MemRequestGen(bufferFlop = true))
    val shmemReqGen = Module(new MemRequestGen(bufferFlop = true))
    
    globalReqGen.io.queueRequest :<>= loadStoreQueues.io.globalReq
    shmemReqGen.io.queueRequest :<>= loadStoreQueues.io.shmemReq
    io.globalMemReq :<>= globalReqGen.io.memRequest
    io.shmemReq :<>= shmemReqGen.io.memRequest

    addressTmaskReadIdxArbIn(0).valid := globalReqGen.io.addressTmaskRead.valid
    addressTmaskReadIdxArbIn(0).bits := globalReqGen.io.addressTmaskRead.bits
    globalReqGen.io.addressTmaskRead.ready := addressTmaskReadIdxArbChosen(0)
    
    storeDataReadIdxArbIn(0).valid := globalReqGen.io.storeDataRead.valid
    storeDataReadIdxArbIn(0).bits := globalReqGen.io.storeDataRead.bits
    globalReqGen.io.storeDataRead.ready := storeDataReadIdxArbChosen(0)

    addressTmaskReadIdxArbIn(1).valid := shmemReqGen.io.addressTmaskRead.valid
    addressTmaskReadIdxArbIn(1).bits := shmemReqGen.io.addressTmaskRead.bits
    shmemReqGen.io.addressTmaskRead.ready := addressTmaskReadIdxArbChosen(1)
    
    storeDataReadIdxArbIn(1).valid := shmemReqGen.io.storeDataRead.valid
    storeDataReadIdxArbIn(1).bits := shmemReqGen.io.storeDataRead.bits
    shmemReqGen.io.storeDataRead.ready := storeDataReadIdxArbChosen(1)

    globalReqGen.io.addressTmask := addressTmask
    globalReqGen.io.storeData := storeData
    shmemReqGen.io.addressTmask := addressTmask
    shmemReqGen.io.storeData := storeData

    // arbiter guarantees only one request succeeds, so inject the allocated load data entry into it (if needed)
    loadStoreQueues.io.globalReq.bits.loadDataIdx := loadDataAllocator.io.allocatedIndex
    loadStoreQueues.io.shmemReq.bits.loadDataIdx := loadDataAllocator.io.allocatedIndex
    loadDataAllocator.io.allocate := false.B
    val globalReqNeedsLoadEntry = MemOp.isLoad(loadStoreQueues.io.globalReq.bits.op) || MemOp.isAtomic(loadStoreQueues.io.globalReq.bits.op)
    val shmemReqNeedsLoadEntry = MemOp.isLoad(loadStoreQueues.io.shmemReq.bits.op) || MemOp.isAtomic(loadStoreQueues.io.shmemReq.bits.op)
    when (loadStoreQueues.io.globalReq.fire) {
        when (globalReqNeedsLoadEntry) {
            loadDataAllocator.io.allocate := true.B
        }
    }
    when (loadStoreQueues.io.shmemReq.fire) {
        when (shmemReqNeedsLoadEntry) {
            loadDataAllocator.io.allocate := true.B
        }
    }

    // -- Receive memory responses --
    
    

    // -- Writeback --
    {
        val valid = RegInit(false.B)
        val packet = RegInit(0.U(p.lsuDerived.packetBits.W))
        val finalPacket = (packet === (p.lsuDerived.numPackets - 1).U)
        val warp = RegInit(0.U(p.warpIdBits.W))

        val writebackData = RegInit(VecInit.fill(p.lsu.numLsuLanes)(0.U(p.archLen.W)))
        val pRegTmask = RegInit(0.U.asTypeOf(new PRegTmask))

        io.coreResp.valid := valid
        io.coreResp.bits.tmask := pRegTmask.tmask
        io.coreResp.bits.destReg := pRegTmask.destReg
        io.coreResp.bits.writebackData := writebackData
        io.coreResp.bits.warpId := warp
        io.coreResp.bits.packet := packet

        when (io.coreResp.fire) {
            packet := packet + 1.U
            when (finalPacket) {
                valid := false.B
            }
        }

        val writebackReady = !valid || (valid && io.coreResp.fire)
        loadStoreQueues.io.writebackReq.ready := writebackReady

        when (loadStoreQueues.io.writebackReq.fire) {
            valid := true.B
            packet := 0.U
            warp := loadStoreQueues.io.writebackReq.bits.warpId

            writebackData := loadDataMem.read(loadStoreQueues.io.writebackReq.bits.loadDataIdx)

            val token = Wire(new LsuQueueToken)
            token := loadStoreQueues.io.writebackReq.bits

            pRegTmask := pRegTmaskMem.read(tokenToPRegTmaskIndex(token))
        }
    }
}
