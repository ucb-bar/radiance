package radiance.muon

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._

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
    p: Parameters,
    muonParams: MuonCoreParams
) {
    require(muonParams.numLanes % muonParams.lsu.numLsuLanes == 0, "numLsuLanes must divide numLanes")

    val multiCycleWriteback = muonParams.numLanes > muonParams.lsu.numLsuLanes
    val numPackets = muonParams.numLanes / muonParams.lsu.numLsuLanes
    val packetBits = log2Up(numPackets)

    // "request tag"
    val sourceIdBits = LsuQueueToken.width(muonParams) + packetBits
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

    val isLoad   = (x: MemOp.Type) => x.isOneOf(loadByte, loadHalf, loadWord)
    val isStore  = (x: MemOp.Type) => x.isOneOf(storeByte, storeHalf, storeWord)
    val isAtomic = (x: MemOp.Type) => x.isOneOf(amoSwap, amoAdd, amoAnd, amoOr, amoXor, amoMax, amoMin)
    val isFence  = (x: MemOp.Type) => x.isOneOf(fence)
}

// Uniquely identifies an entry in load/store queues (warpId, addressSpace, ldq, index)
// Used as request tag into downstream memory interfaces
class LsuQueueToken(implicit p: Parameters) extends CoreBundle()(p) {
    val warpId = UInt(muonParams.warpIdBits.W)
    val addressSpace = AddressSpace()
    val ldq = Bool()
    val index = UInt(muonParams.lsu.queueIndexBits.W)
}

object LsuQueueToken {
    // a little sus
    def width(muonParams: MuonCoreParams): Int = {
        muonParams.warpIdBits + 1 + 1 + muonParams.lsu.queueIndexBits
    }
}

// LSQ allocate slots in program order and produce a token for the allocated slot.
// This token must be held in reservation station and given to LSU when a memory instruction issues from RS
// (possibly out-of-order!).
// We expose a separate interface per warp to simplify checking for structural hazard on queue entries from per-warp IBUF
// Even if IBUF looks past its head to issue to RS, it still needs to ensure in-order reservations occur
// Allocated index into address and storeData SRAM is also stored in the newly allocated LSQ entry 
class LsuQueueReservation(implicit p: Parameters) extends CoreBundle()(p) {
    val addressSpace = AddressSpace()
    val op = MemOp()

    val addressIdx = UInt(muonParams.lsu.addressIdxBits.W)
    val storeDataIdx = UInt(muonParams.lsu.storeDataIdxBits.W)

    // note: `token` and ready signal (fullness of queue) are 
    // combinationally coupled to addressSpace / op
    val token = Flipped(new LsuQueueToken)
}

// Once operand collector / forwarding network has finished, RS issues instruction to LSU,
// LSU informs LSQ to indicate that memory request has received its operands.
// It must respond with addressIdx and storeDataIdx that were allocated at the same time as LSQ entry 
// so that address and data from core can be stored.
class LsuQueueOperandUpdate(implicit p: Parameters) extends LsuQueueToken {
    val addressIdx = Flipped(UInt(muonParams.lsu.addressIdxBits.W))
    val storeDataIdx = Flipped(UInt(muonParams.lsu.storeDataIdxBits.W))
}

// Each load and store queue attempts to issue requests back to LSU in a correct order once 
// operands for that request are received and hazards resolved.
// All requests (shared / global) (stores / loads) (across all warps) are arbitrated into a single
// outbound request from LSU (due to only having a single read port for address / storeData SRAMs).
// Deallocation of address / store data, and allocation for load data entry both occur here.
class LsuQueueRequest(implicit p: Parameters) extends LsuQueueToken {
    val op = MemOp()

    val addressIdx = UInt(muonParams.lsu.addressIdxBits.W)
    val storeDataIdx = UInt(muonParams.lsu.storeDataIdxBits.W)

    val loadDataIdx = Flipped(UInt(muonParams.lsu.loadDataIdxBits.W))
}

// Once memory subsystem responds with load data (or store ack), LSU notifies Load and Store queues
// so it can either reclaim the entry immediately (stores) or start driving writebacks (loads / atomics)
class LsuQueueMemResponse(implicit p: Parameters) extends LsuQueueToken {
    val loadDataIdx = Flipped(UInt(muonParams.lsu.loadDataIdxBits.W))
}

// This interface allows for an optimization for our shared memory design, which is guaranteed to not
// reorder requests. As such, we can advance head without waiting for load data (or store ack)
class LsuQueueMemUpdate(implicit p: Parameters) extends LsuQueueToken

// Finally, LSQ entries drive writeback for load / atomic operations. 
// Deallocation of load data entries occurs here.
// We don't send anything down writeback path for fences / stores.
// TODO: maybe want something to allow for instrumentation of fences / stores
class LsuQueueWritebackRequest(implicit p: Parameters) extends LsuQueueToken {
    val loadDataIdx = UInt(muonParams.lsu.loadDataIdxBits.W)
}

// perf: memReq, writebackReq goes through numWarps * 4 radix arbiter
// similarly, receivedOperands / receivedMemUpdate / receivedMemResponse all fanout to numWarps * 4
class LoadStoreQueue(implicit p: Parameters) extends CoreModule()(p) {
    val io = IO(new Bundle {
        val queueReservations = Vec(muonParams.numWarps, Flipped(Decoupled(new LsuQueueReservation)))
        
        val memReq = Decoupled(new LsuQueueRequest)
        
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
                val entries            = if (loadQueue) { muonParams.lsu.numGlobalLdqEntries    } else { muonParams.lsu.numGlobalStqEntries    }
                val circIndexBits      = if (loadQueue) { muonParams.lsu.globalLdqCircIndexBits } else { muonParams.lsu.globalStqCircIndexBits }
                val otherCircIndexBits = if (loadQueue) { muonParams.lsu.globalStqCircIndexBits } else { muonParams.lsu.globalLdqCircIndexBits }
                val indexBits          = if (loadQueue) { muonParams.lsu.globalLdqIndexBits     } else { muonParams.lsu.globalStqIndexBits     }

                (entries, circIndexBits, otherCircIndexBits, indexBits)
            }
            case Shared => {
                val entries            = if (loadQueue) { muonParams.lsu.numSharedLdqEntries    } else { muonParams.lsu.numSharedStqEntries    }
                val circIndexBits      = if (loadQueue) { muonParams.lsu.sharedLdqCircIndexBits } else { muonParams.lsu.sharedStqCircIndexBits }
                val otherCircIndexBits = if (loadQueue) { muonParams.lsu.sharedStqCircIndexBits } else { muonParams.lsu.sharedLdqCircIndexBits }
                val indexBits          = if (loadQueue) { muonParams.lsu.sharedLdqIndexBits     } else { muonParams.lsu.sharedStqIndexBits     }

                (entries, circIndexBits, otherCircIndexBits, indexBits)
            }
        }

        def makeToken(idx: UInt): LsuQueueToken = {
            val token = Wire(new LsuQueueToken)
            token.warpId := warpId.U
            token.addressSpace := addressSpace.toChisel
            token.ldq := loadQueue.B
            token.index := idx.padTo(muonParams.lsu.queueIndexBits)

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
            val addressIdx = Input(UInt(muonParams.lsu.addressIdxBits.W))
            val storeDataIdx = Input(UInt(muonParams.lsu.storeDataIdxBits.W))

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
        //   (but we can't deallocate entry yet; need loadDataIndex to do writeback)
        // - writeback: we received a mem response and can begin to write back
        val valid              = RegInit(VecInit.fill(entries)(false.B))
        val op                 = RegInit(VecInit.fill(entries)(MemOp.loadWord))
        val otherQueueTail     = RegInit(VecInit.fill(entries)(0.U(otherCircIndexBits.W)))
        val operandsReady      = RegInit(VecInit.fill(entries)(false.B))
        val issued             = RegInit(VecInit.fill(entries)(false.B))
        val addressIdx         = RegInit(VecInit.fill(entries)(0.U(muonParams.lsu.addressIdxBits.W)))
        val storeDataIdx       = RegInit(VecInit.fill(entries)(0.U(muonParams.lsu.storeDataIdxBits.W)))
        val loadDataIdx        = RegInit(VecInit.fill(entries)(0.U(muonParams.lsu.loadDataIdxBits.W)))
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
            io.req.bits.storeDataIdx := DontCare
            
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
            io.req.bits.op := readyStoreOp
            io.req.bits.addressIdx := addressIdx(readyStoreIndex)
            io.req.bits.storeDataIdx := storeDataIdx(readyStoreIndex)

            // fences are fake entries, and don't generate any downstream requests
            // instead, they immediately retire 
            when (MemOp.isFence(readyStoreOp)) {
                io.req.valid := false.B
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
        when (logicalHead =/= tail && (!valid(idxBits(logicalHead)) || done(idxBits(logicalHead)))) {
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
    
    val warpEmptys = Wire(Vec(muonParams.numWarps, Bool()))
    val allEmpty = warpEmptys.andR
    io.queuesEmpty := allEmpty

    // instantiate queues
    val globalLoadQueues = Seq.tabulate(muonParams.numWarps)(w => Module(new PerWarpLoadQueue(w, AddressSpaceCfg.Global)))
    val globalStoreQueues = Seq.tabulate(muonParams.numWarps)(w => Module(new PerWarpStoreQueue(w, AddressSpaceCfg.Global)))
    val shmemLoadQueues = Seq.tabulate(muonParams.numWarps)(w => Module(new PerWarpLoadQueue(w, AddressSpaceCfg.Shared)))
    val shmemStoreQueues = Seq.tabulate(muonParams.numWarps)(w => Module(new PerWarpStoreQueue(w, AddressSpaceCfg.Shared)))

    for (warp <- 0 until muonParams.numWarps) {
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
        val queueIndex = Wire(UInt(muonParams.lsu.queueIndexBits.W))

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
    
    // current arbitration: shared > global, then loads > stores, then lower warpId > higher warpId
    // TODO: better arbitration scheme?
    val nGlobalReqs = muonParams.numWarps * 2 // 1 global load queue, 1 global store queue per warp 
    val nShmemReqs = muonParams.numWarps * 2
    val memReqArbiter = Module(new Arbiter(new LsuQueueRequest, nGlobalReqs + nShmemReqs))

    io.memReq :<>= memReqArbiter.io.out

    (memReqArbiter.io.in zip (shmemLoadQueues ++ shmemStoreQueues ++ globalLoadQueues ++ globalStoreQueues)).foreach(x => {
        val (arbPort, queue) = x
        arbPort :<>= queue.io.req
    })

    
    // Route signals to per-warp queues and handle reverse direction
    for (warp <- 0 until muonParams.numWarps) {
        val globalLoadQueue = globalLoadQueues(warp)
        val globalStoreQueue = globalStoreQueues(warp)
        val shmemLoadQueue = shmemLoadQueues(warp)
        val shmemStoreQueue = shmemStoreQueues(warp)
        
        def routeDataToQueues[T <: Data](
            data: T, 
            signal: (PerWarpQueue) => T, 
        ): Unit = {
            signal(globalLoadQueue) := data
            signal(globalStoreQueue) := data
            signal(shmemLoadQueue) := data
            signal(shmemStoreQueue) := data
        }

        def routeValidToQueues(
            token: LsuQueueToken,
            valid: Bool,
            signalValid: (PerWarpQueue) => Bool, 
        ): Unit = {
            val thisWarp = token.warpId === warp.U
            val globalMemory = token.addressSpace === AddressSpace.globalMemory
            val sharedMemory = token.addressSpace === AddressSpace.sharedMemory
            val ldq = token.ldq

            signalValid(globalLoadQueue) := thisWarp && globalMemory && valid && ldq
            signalValid(globalStoreQueue) := thisWarp && globalMemory && valid && !ldq
            signalValid(shmemLoadQueue) := thisWarp && sharedMemory && valid && ldq
            signalValid(shmemStoreQueue) := thisWarp && sharedMemory && valid && !ldq
        }

        def routeTokenToQueues[T <: LsuQueueToken](
            token: T, 
            valid: Bool, 
            signal: (PerWarpQueue) => T, 
            signalValid: (PerWarpQueue) => Bool
        ): Unit = {
            routeValidToQueues(token, valid, signalValid)
            routeDataToQueues(token.viewAsSupertype(new LsuQueueToken), signal.andThen(_.viewAsSupertype(new LsuQueueToken)))
        }


        // Forward direction: route incoming signals to correct queue
        routeTokenToQueues(io.receivedOperands.bits, io.receivedOperands.valid, q => q.io.receivedOperands.bits, q => q.io.receivedOperands.valid)
        routeTokenToQueues(io.receivedMemUpdate.bits, io.receivedMemUpdate.valid, q => q.io.receivedMemUpdate.bits, q => q.io.receivedMemUpdate.valid)
        routeTokenToQueues(io.receivedMemResponse.bits, io.receivedMemResponse.valid, q => q.io.receivedMemResponse.bits, q => q.io.receivedMemResponse.valid)
    }
    
    // Reverse direction: Only one queue should be responding at a time, so we can use priority encoding
    val allQueues = globalLoadQueues ++ globalStoreQueues ++ shmemLoadQueues ++ shmemStoreQueues
    val allQueueIOs = VecInit(allQueues.map(_.io))
    
    def routeDataFromQueues[T <: Data](
        valid: (PerWarpQueue) => Bool, 
    ) = {
        val respondingQueues = allQueues.map(valid)
        val responseIndex = PriorityEncoder(respondingQueues.asUInt)
        val responseQueue = allQueueIOs(responseIndex)

        responseQueue
    }

    {
        val responseQueue = routeDataFromQueues(q => q.io.receivedOperands.valid)
        io.receivedOperands.bits.addressIdx := responseQueue.receivedOperands.bits.addressIdx
        io.receivedOperands.bits.storeDataIdx := responseQueue.receivedOperands.bits.storeDataIdx
    }
    {
        val responseQueue = routeDataFromQueues(q => q.io.receivedMemResponse.valid)
        io.receivedMemResponse.bits.loadDataIdx := responseQueue.receivedOperands.bits.addressIdx
    }

    // arbitrate writeback request between queues
    // currently: shared loads > shared atomics > global loads > global atomics
    // TODO: better arbitration?
    val nWritebackReqs = muonParams.numWarps * 2 // 1 global load queue, 1 global store queue per warp 
    val writebackArbiter = Module(new Arbiter(new LsuQueueWritebackRequest, nWritebackReqs))
    io.writebackReq :<>= writebackArbiter.io.out

    (writebackArbiter.io.in zip (shmemLoadQueues ++ shmemStoreQueues ++ globalLoadQueues ++ globalStoreQueues)).foreach(x => {
        val (arbPort, queue) = x
        arbPort :<>= queue.io.writebackReq
    })
}

// Forwards to per-warp LsuQueueReservation interface, following allocation
class LsuReservation(implicit p: Parameters) extends CoreBundle()(p) {
    val addressSpace = AddressSpace()
    val op = MemOp()

    // combinationally coupled to addressSpace / op
    val token = Flipped(new LsuQueueToken)
}

// Execute interface
class LsuRequest(implicit p: Parameters) extends CoreBundle()(p) {
    val token = new LsuQueueToken

    val tmask = Vec(muonParams.numLanes, Bool())
    val address = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
    val imm = UInt(muonParams.archLen.W)

    val destReg = UInt(muonParams.pRegBits.W)
    val storeData = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
}

// Writeback interface to rest of core
class LsuResponse(implicit p: Parameters) extends CoreBundle()(p) {
    val warpId = UInt(muonParams.warpIdBits.W)

    val packet = UInt(lsuDerived.packetBits.W)
    val tmask = Vec(muonParams.numLanes, Bool())
    val destReg = UInt(muonParams.pRegBits.W)
    val writebackData = Vec(muonParams.lsu.numLsuLanes, UInt(muonParams.archLen.W))
}

// Downstream memory interface
class LsuMemTag(implicit p: Parameters) extends CoreBundle()(p) {
    val token = new LsuQueueToken
    val packet = UInt(lsuDerived.packetBits.W)
}

class LsuMemRequest(implicit p: Parameters) extends CoreBundle()(p) {
    val tag = UInt(lsuDerived.sourceIdBits.W)
    
    val op = MemOp()
    val address = Vec(muonParams.lsu.numLsuLanes, UInt(muonParams.archLen.W))
    val data = Vec(muonParams.lsu.numLsuLanes, UInt(muonParams.archLen.W))
    val tmask = Vec(muonParams.lsu.numLsuLanes, Bool())
}

class LsuMemResponse(implicit p: Parameters) extends CoreBundle()(p) {
    val tag = UInt(lsuDerived.sourceIdBits.W)

    val data = Vec(muonParams.lsu.numLsuLanes, UInt(muonParams.archLen.W))
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
    def selectPacket[T <: Data](vec: Vec[T], packet: UInt)(p: HasMuonCoreParameters): Vec[T] = {
        require(vec.length == p.muonParams.numLanes, "Vec length must be equal to numLanes")
        val out = Wire(Vec(p.muonParams.lsu.numLsuLanes, vec.head.cloneType))
        for (i <- 0 until p.muonParams.lsu.numLsuLanes) {
            out(i) := MuxLookup(packet, 0.U.asTypeOf(vec.head))(
                (0 until p.lsuDerived.numPackets).map(c => c.U -> vec(c * p.muonParams.lsu.numLsuLanes + i))
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

class LoadStoreUnit(implicit p: Parameters) extends CoreModule()(p) {

    val io = IO(new Bundle {
        val coreReq = Flipped(Decoupled(new LsuRequest))
        val coreReservations = Vec(muonParams.numWarps, Flipped(Decoupled(new LsuQueueReservation)))
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
    val addressTmaskAllocator = Module(new FreeListAllocator(muonParams.lsu.addressEntries))
    val storeDataAllocator = Module(new FreeListAllocator(muonParams.lsu.storeDataEntries))
    val loadDataAllocator = Module(new FreeListAllocator(muonParams.lsu.loadDataEntries))

    // pRegTmask is allocated statically, 1 for every entry in every warp.
    def tokenToPRegTmaskIndex = (token: LsuQueueToken) => {
        val warpStride = muonParams.lsu.numGlobalLdqEntries + muonParams.lsu.numGlobalStqEntries + muonParams.lsu.numSharedLdqEntries + muonParams.lsu.numSharedStqEntries
        val offset = MuxCase(0.U, Seq(
            (token.addressSpace === AddressSpace.globalMemory && token.ldq) -> 0.U,
            (token.addressSpace === AddressSpace.globalMemory && !token.ldq) -> (muonParams.lsu.numGlobalLdqEntries).U,
            (token.addressSpace === AddressSpace.sharedMemory && token.ldq) -> (muonParams.lsu.numGlobalLdqEntries + muonParams.lsu.numGlobalStqEntries).U,
            (token.addressSpace === AddressSpace.sharedMemory && !token.ldq) -> (muonParams.lsu.numGlobalLdqEntries + muonParams.lsu.numGlobalStqEntries + muonParams.lsu.numSharedLdqEntries).U
        ))

        token.warpId * warpStride.U + offset + token.index
    }
    
    // SRAMs
    class AddressTmask extends CoreBundle {
        val address = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
        val tmask = Vec(muonParams.numLanes, Bool())
    }

    class PRegTmask extends CoreBundle {
        val tmask = Vec(muonParams.numLanes, Bool())
        val destReg = UInt(muonParams.pRegBits.W)
    }

    val addressTmaskMem = SyncReadMem(muonParams.lsu.addressEntries, new AddressTmask)
    val storeDataMem = SyncReadMem(muonParams.lsu.storeDataEntries, Vec(muonParams.numLanes, UInt(muonParams.archLen.W)))
    val loadDataMem = SyncReadMem(muonParams.lsu.loadDataEntries * lsuDerived.numPackets, Vec(muonParams.lsu.numLsuLanes, UInt(muonParams.archLen.W)))
    
    val totalQueueEntries = muonParams.numWarps * (muonParams.lsu.numGlobalLdqEntries + muonParams.lsu.numGlobalStqEntries + muonParams.lsu.numSharedLdqEntries + muonParams.lsu.numSharedStqEntries)
    val pRegTmaskMem = SyncReadMem(totalQueueEntries, new PRegTmask)

    // arbitrate between reservations, injecting allocated address SRAM index
    // and allocated store data index (for stores / atomics)
    // TODO: better arbitration?
    addressTmaskAllocator.io.allocate := false.B
    storeDataAllocator.io.allocate := false.B

    for (warp <- 0 until muonParams.numWarps) {
        val queueReservation = loadStoreQueues.io.queueReservations(warp)
        val coreReservation = io.coreReservations(warp)

        queueReservation.bits.addressSpace := coreReservation.bits.addressSpace
        queueReservation.bits.op := coreReservation.bits.op
    }

    val coreReservationValids = Cat(io.coreReservations.map(r => r.valid))
    val queueReservationReadys = Cat(loadStoreQueues.io.queueReservations.map(r => r.ready))
    val reservationValidOH = PriorityEncoderOH(coreReservationValids & queueReservationReadys)
    
    for (warp <- 0 until muonParams.numWarps) {
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
        val addressTmask = Wire(new AddressTmask)
        
        // address generation
        val imm = io.coreReq.bits.imm
        val address = VecInit(io.coreReq.bits.address.map(_ + imm))
        addressTmask.address := address
        addressTmask.tmask := io.coreReq.bits.tmask

        addressTmaskMem.write(queueReceivedOperands.bits.addressIdx, addressTmask)
        storeDataMem.write(queueReceivedOperands.bits.storeDataIdx, io.coreReq.bits.storeData)

        val pRegTmaskWriteIdx = tokenToPRegTmaskIndex(io.coreReq.bits.token)
        val pRegTmask = Wire(new PRegTmask)
        pRegTmask.destReg := io.coreReq.bits.destReg
        pRegTmask.tmask := io.coreReq.bits.tmask
        
        pRegTmaskMem.write(pRegTmaskWriteIdx, pRegTmask)
    }

    // -- Generate downstream memory requests -- 

    val addressTmaskReadIdx = Wire(UInt(muonParams.lsu.addressIdxBits.W))
    val storeDataReadIdx = Wire(UInt(muonParams.lsu.storeDataIdxBits.W))
    val addressTmask = storeDataMem.read(addressTmaskReadIdx)
    val storeData = storeDataMem.read(storeDataReadIdx)
     
    class MemRequestGen(bufferFlop: Boolean) extends CoreModule {
        val io = IO(new Bundle {
            val queueRequest = Flipped(Decoupled(new LsuQueueRequest))
            val memRequest = Decoupled(new LsuMemRequest)

            val addressTmaskRead = Valid(UInt(muonParams.lsu.addressIdxBits.W))
            val addressTmask = Input(new AddressTmask)

            val storeDataRead = Valid(UInt(muonParams.lsu.storeDataIdxBits.W))
            val storeData = Input(Vec(muonParams.numLanes, UInt(muonParams.archLen.W)))
        })

        if (bufferFlop) {
            val valid = RegInit(false.B)
            val packet = RegInit(0.U(lsuDerived.packetBits.W))
            val finalPacket = (packet === (lsuDerived.numPackets - 1).U)
            
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
            val storeData = RegInit(VecInit.fill(muonParams.numLanes)(0.U(muonParams.archLen.W)))
            when (queueRequestFirePrev) {
                addressTmask := io.addressTmask
                storeData := io.storeData

                io.memRequest.bits.address := Utils.selectPacket(io.addressTmask.address, packet)(this)
                io.memRequest.bits.data := Utils.selectPacket(io.storeData, packet)(this)
                io.memRequest.bits.tmask := Utils.selectPacket(io.addressTmask.tmask, packet)(this)
            }.otherwise {
                io.memRequest.bits.address := Utils.selectPacket(addressTmask.address, packet)(this)
                io.memRequest.bits.data := Utils.selectPacket(storeData, packet)(this)
                io.memRequest.bits.tmask := Utils.selectPacket(addressTmask.tmask, packet)(this)
            }

            io.memRequest.valid := valid 
            io.memRequest.bits.op := op

            val tag = Wire(new LsuMemTag)
            tag.token := token
            tag.packet := packet
            io.memRequest.bits.tag := tag.asUInt
        }
        else {
            ???
        }
    }

    // TODO: continually read from SRAM, or store to intermediate flop?
    val reqGen = Module(new MemRequestGen(bufferFlop = true))
    
    reqGen.io.queueRequest :<>= loadStoreQueues.io.memReq
    
    // route to correct downstream interface
    io.globalMemReq.bits := reqGen.io.memRequest.bits
    io.shmemReq.bits := reqGen.io.memRequest.bits
    
    when (loadStoreQueues.io.memReq.bits.addressSpace === AddressSpace.globalMemory) {
        io.globalMemReq.valid := true.B
        io.shmemReq.valid := false.B
        reqGen.io.memRequest.ready := io.globalMemReq.ready
    }.otherwise {
        io.globalMemReq.valid := false.B
        io.shmemReq.valid := true.B
        reqGen.io.memRequest.ready := io.shmemReq.ready
    }

    reqGen.io.addressTmask := addressTmask
    reqGen.io.storeData := storeData
    
    // allocate load data entry, if needed
    loadStoreQueues.io.memReq.bits.loadDataIdx := loadDataAllocator.io.allocatedIndex
    val memReqNeedsLoadEntry = MemOp.isLoad(loadStoreQueues.io.memReq.bits.op) || MemOp.isAtomic(loadStoreQueues.io.memReq.bits.op)
    loadDataAllocator.io.allocate := loadStoreQueues.io.memReq.fire && memReqNeedsLoadEntry
    
    // -- Memory Update --
    
    // for now, we don't use this at all
    loadStoreQueues.io.receivedMemUpdate.valid := false.B
    loadStoreQueues.io.receivedMemUpdate.bits := DontCare

    // -- Receive memory responses --
    {
        // arbitration: responses from global > responses from shared
        val valids = Cat(io.shmemResp.valid, io.globalMemReq.valid)
        val readys = PriorityEncoderOH(valids)
        io.globalMemResp.ready := readys(0)
        io.shmemResp.ready := readys(1)

        val receivedResp = valids.orR
        
        val loadDataWriteIdx = Wire(UInt(muonParams.lsu.loadDataIdxBits.W))
        val loadDataWriteVal = Mux1H(valids, Seq(
            io.globalMemResp.bits.data,
            io.shmemResp.bits.data
        ))

        when (receivedResp.orR) {
            loadDataMem.write(loadDataWriteIdx, loadDataWriteVal)
        }

        val respTagBits = Mux1H(valids, Seq(
            io.globalMemResp.bits.tag,
            io.shmemResp.bits.tag
        ))
        
        val respTag = respTagBits.asTypeOf(new LsuMemTag)
        
        loadStoreQueues.io.receivedMemResponse.valid := valids.orR
        loadStoreQueues.io.receivedMemResponse.bits.viewAsSupertype(new LsuQueueToken) := respTag.token
        
        // packet forms LSBs of index
        loadDataWriteIdx := (loadStoreQueues.io.receivedMemResponse.bits.loadDataIdx * lsuDerived.numPackets.U) + respTag.packet
    }
    

    // -- Writeback --
    {
        val valid = RegInit(false.B)
        val packet = RegInit(0.U(lsuDerived.packetBits.W))
        val finalPacket = (packet === (lsuDerived.numPackets - 1).U)
        val warp = RegInit(0.U(muonParams.warpIdBits.W))

        val writebackData = RegInit(VecInit.fill(muonParams.lsu.numLsuLanes)(0.U(muonParams.archLen.W)))
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
