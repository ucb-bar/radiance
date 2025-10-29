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

    val loadDataPhysicalIdxBits = if (numPackets == 1) {
        muonParams.lsu.loadDataIdxBits
    } else {
        muonParams.lsu.loadDataIdxBits + packetBits
    } 

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
// Used as part the request tag into downstream memory interfaces
class LsuQueueToken(implicit p: Parameters) extends CoreBundle {
    val warpId = UInt(muonParams.warpIdBits.W)
    val addressSpace = AddressSpace()
    val ldq = Bool()
    val index = UInt(muonParams.lsu.queueIndexBits.W)
}

object LsuQueueToken {
    def width(muonParams: MuonCoreParams): Int = {
        muonParams.warpIdBits + 1 + 1 + muonParams.lsu.queueIndexBits
    }
}

// Note: below LSQ interfaces are all bidirectional, with separate request and response channels. 
// At the moment, both directions are combinationally coupled, but this may be relaxed in 
// future if performance is an issue.

// LSQ reserves slots in program order and produce a token for the reserved slot
// This token must be held in reservation station and given to LSU when a memory instruction issues from RS
// (possibly out-of-order!).

// We expose a separate interface per warp to simplify checking for structural hazard on queue entries from per-warp IBUF
// Even if IBUF looks past its head to issue to RS, it still needs to ensure in-order reservations occur
// Allocated index into address and storeData SRAM is also stored in the newly reserved LSQ entry.
class LSQReservationReq(implicit p: Parameters) extends CoreBundle {
    val addressSpace = AddressSpace()
    val op = MemOp()

    val addressIdx = UInt(muonParams.lsu.addressIdxBits.W)
    val storeDataIdx = UInt(muonParams.lsu.storeDataIdxBits.W)
}

class LSQReservationResp(implicit p: Parameters) extends CoreBundle {
    val token = new LsuQueueToken
}

// Once operand collector / forwarding network has finished, RS issues instruction to LSU,
// LSU informs LSQ to indicate that memory request has received its operands.
// It must respond with addressIdx and storeDataIdx that were allocated at the same time as LSQ entry 
// so that address and data from core can be stored.
class LSQOperandUpdateReq(implicit p: Parameters) extends CoreBundle {
    val token = new LsuQueueToken
}

class LSQOperandUpdateResp(implicit p: Parameters) extends CoreBundle {
    val addressIdx = UInt(muonParams.lsu.addressIdxBits.W)
    val storeDataIdx = UInt(muonParams.lsu.storeDataIdxBits.W)
}

// Each load and store queue attempts to issue requests back to LSU in a correct order once 
// operands for that request are received and potential memory hazards resolved.
// All requests (shared / global) (stores / loads) (across all warps) are arbitrated into a single
// outbound request from LSU (due to only having a single read port for address / storeData SRAMs).
// Deallocation of address / store data, and allocation for load data entry both occur here.
class LSQReq(implicit p: Parameters) extends CoreBundle {
    val token = new LsuQueueToken
    val op = MemOp()

    val addressIdx = UInt(muonParams.lsu.addressIdxBits.W)
    val storeDataIdx = UInt(muonParams.lsu.storeDataIdxBits.W)
}

class LSQResp(implicit p: Parameters) extends CoreBundle {
    val loadDataIdx = UInt(muonParams.lsu.loadDataIdxBits.W)
}

// Once memory subsystem responds with load data (or store ack), LSU notifies Load and Store queues
// to obtain the SRAM index it should write that data into. 
// LSQ can either reclaim the entry immediately (stores) or start driving a writeback request (loads / atomics)
class LSQMemReturnReq(implicit p: Parameters) extends CoreBundle {
    val token = new LsuQueueToken    
}

class LSQMemReturnResp(implicit p: Parameters) extends CoreBundle {
    val loadDataIdx = UInt(muonParams.lsu.loadDataIdxBits.W)
}

// This interface allows for an optimization for our shared memory design, which is guaranteed to not
// reorder requests. As such, we can advance head without waiting for load data (or store ack)
class LSQMemUpdate(implicit p: Parameters) extends CoreBundle {
    val token = new LsuQueueToken
}

// Finally, LSQ entries drive writeback for load / atomic operations. 
// Deallocation of load data entries occurs here.
// We don't send anything down writeback path for fences / stores.
// TODO: maybe want something to allow for instrumentation of fences / stores
class LSQWritebackReq(implicit p: Parameters) extends CoreBundle {
    val token = new LsuQueueToken
    val loadDataIdx = UInt(muonParams.lsu.loadDataIdxBits.W)
}

// @perf: memReq, writebackReq goes through numWarps * 4 radix arbiter
// similarly, receivedOperands / receivedMemUpdate / receivedMemResponse all fanout to numWarps * 4

// TODO: create versions or parameterizations where the LDQ/STQ are shared between warps, 
// between shared/global, or both
class LoadStoreQueue(implicit p: Parameters) extends CoreModule()(p) {
    val io = IO(new Bundle {
        val queueReservations = Vec(muonParams.numWarps, new Bundle {
            val req = Flipped(Decoupled(new LSQReservationReq))
            val resp = Valid(new LSQReservationResp)
        })
        
        val receivedOperands = new Bundle {
            val req = Flipped(Valid(new LSQOperandUpdateReq))
            val resp = Valid(new LSQOperandUpdateResp)
        }
        
        val sendMemRequest = new Bundle {
            val req = Decoupled(new LSQReq)
            val resp = Flipped(Valid(new LSQResp))
        }
        
        val receivedMemUpdate = Flipped(Valid(new LSQMemUpdate))
        val receivedMemResponse = new Bundle {
            val req = Flipped(Valid(new LSQMemReturnReq))
            val resp = Valid(new LSQMemReturnResp)
        }
        
        val writebackReq = Decoupled(new LSQWritebackReq) 

        // used to flush LSU
        val queuesEmpty = Output(Bool())
    })

    // helper functions for circular fifo indices
    val msb = (x: UInt) => x(x.getWidth - 1)
    val wrapBit = msb
    val idxBits = (x: UInt) => x(x.getWidth - 2, 0)

    

    // per-warp Circular FIFO parameterized to be a load queue or store queue
    class PerWarpQueue(
        val warpId: Int,
        val addressSpace: AddressSpaceCfg,
        val loadQueue: Boolean
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

        // extract local index from token index (token index is max width of all queue indices)
        def localIndex(idx: UInt): UInt = {
            idx(indexBits-1, 0)
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

            val receivedOperands = new Bundle {
                val req = Flipped(Valid(new LSQOperandUpdateReq))
                val resp = Valid(new LSQOperandUpdateResp)
            }
            
            val sendMemRequest = new Bundle {
                val req = Decoupled(new LSQReq)
                val resp = Flipped(Valid(new LSQResp))
            }
            
            val receivedMemUpdate = Flipped(Valid(new LSQMemUpdate))
            val receivedMemResponse = new Bundle {
                val req = Flipped(Valid(new LSQMemReturnReq))
                val resp = Valid(new LSQMemReturnResp)
            }
            
            val writebackReq = Decoupled(new LSQWritebackReq) 
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
        // - loadPackets: number of packets which have been received for this entry
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
        val loadPackets        = RegInit(VecInit.fill(entries)(0.U(lsuDerived.packetBits.W)))
        val done               = RegInit(VecInit.fill(entries)(false.B))
        val writeback          = RegInit(VecInit.fill(entries)(false.B))
        
        // allocation logic
        when (io.enqueue) {
            valid(idxBits(tail)) := true.B
            op(idxBits(tail)) := io.op
            otherQueueTail(idxBits(tail)) := io.otherTail
            operandsReady(idxBits(tail)) := false.B
            issued(idxBits(tail)) := false.B

            addressIdx(idxBits(tail)) := io.addressIdx
            if (!loadQueue) {
                storeDataIdx(idxBits(tail)) := io.storeDataIdx
            }
            loadPackets(idxBits(tail)) := 0.U(lsuDerived.packetBits.W)
            done(idxBits(tail)) := false.B

            tail := tail + 1.U
        }
        
        val overflow = RegNext(
            io.enqueue && full,
            false.B
        )
        assert(!overflow, "overflow of per-warp queue")

        // mark when operands received from reservation station and respond with
        // addressIdx and storeDataIdx
        io.receivedOperands.resp.valid := io.receivedOperands.req.valid
        io.receivedOperands.resp.bits.addressIdx := addressIdx(localIndex(io.receivedOperands.req.bits.token.index))
        io.receivedOperands.resp.bits.storeDataIdx := {
            if (loadQueue) { DontCare } else { storeDataIdx(localIndex(io.receivedOperands.req.bits.token.index)) }
        }

        when (io.receivedOperands.req.valid) {
            operandsReady(localIndex(io.receivedOperands.req.bits.token.index)) := true.B
        }
        
        val badIndex = RegNext(
            io.receivedOperands.req.valid && !valid(localIndex(io.receivedOperands.req.bits.token.index)), 
            false.B
        );
        assert(!badIndex, "invalid index from reservation station")

        // find entries ready to issue a mem request
        if (loadQueue) {
            // loads can only issue once all older stores have retired, but consecutive loads can be reordered
            val readyLoads = Wire(Vec(entries, Bool()))
            for (i <- 0 until entries) {
                // we don't need to worry about otherHead advancing past otherQueueTail
                // 2 cases:
                // - entry pointed to by otherHead was reserved prior to this entry, but in this
                // case otherQueueTail would be larger
                // - entry pointed to by otherHead was reserved after this entry, in which case
                // we block it from issuing
                val olderStoresRetired = io.otherHead === otherQueueTail(i)
                readyLoads(i) := valid(i) && operandsReady(i) && olderStoresRetired && !issued(i)
            }

            // issue first ready load
            val anyLoadReady = readyLoads.asUInt.orR
            val readyLoadIndex = PriorityEncoder(readyLoads)

            io.sendMemRequest.req.valid := anyLoadReady

            io.sendMemRequest.req.bits.token := makeToken(readyLoadIndex)
            io.sendMemRequest.req.bits.op := op(readyLoadIndex)
            io.sendMemRequest.req.bits.addressIdx := addressIdx(readyLoadIndex)
            io.sendMemRequest.req.bits.storeDataIdx := DontCare
            
            when (io.sendMemRequest.req.fire) {
                loadDataIdx(readyLoadIndex) := io.sendMemRequest.resp.bits.loadDataIdx
                issued(readyLoadIndex) := true.B
            }
        }
        else {
            // Stores always issued in order, and must issue after older loads retired to avoid
            // WAR hazards through memory
            val olderLoadsRetired = io.otherHead === otherQueueTail(idxBits(logicalHead))
            
            val readyStore = {
                valid(idxBits(logicalHead)) &&
                operandsReady(idxBits(logicalHead)) &&
                olderLoadsRetired &&
                !issued(idxBits(logicalHead))
            }
            val readyStoreIndex = idxBits(logicalHead)
            val readyStoreOp = op(readyStoreIndex)

            io.sendMemRequest.req.valid := readyStore

            io.sendMemRequest.req.bits.token := makeToken(readyStoreIndex)
            io.sendMemRequest.req.bits.op := readyStoreOp
            io.sendMemRequest.req.bits.addressIdx := addressIdx(readyStoreIndex)
            io.sendMemRequest.req.bits.storeDataIdx := storeDataIdx(readyStoreIndex)

            // fences are fake entries, and don't generate any downstream requests
            // instead, they immediately retire 
            when (MemOp.isFence(readyStoreOp)) {
                io.sendMemRequest.req.valid := false.B
                valid(readyStoreIndex) := false.B
            }

            when (io.sendMemRequest.req.fire) {
                when (MemOp.isAtomic(readyStoreOp)) {
                    loadDataIdx(readyStoreIndex) := io.sendMemRequest.resp.bits.loadDataIdx
                }
                issued(readyStoreIndex) := true.B
            }
        }

        // set done when receiving mem update or mem response, allowing logical head to move forward
        when (io.receivedMemUpdate.valid) {
            done(localIndex(io.receivedMemUpdate.bits.token.index)) := true.B    
        }

        val memResponseIndex = localIndex(io.receivedMemResponse.req.bits.token.index)
        when (io.receivedMemResponse.req.valid) {
            
            loadPackets(memResponseIndex) := loadPackets(memResponseIndex) + 1.U

            when (loadPackets(memResponseIndex) === (lsuDerived.numPackets - 1).U) {
                done(memResponseIndex) := true.B

                if (loadQueue) {
                    // every load needs to write back
                    writeback(memResponseIndex) := true.B
                }
                else {
                    // only atomics need to write back; others can retire immediately
                    val atomic = MemOp.isAtomic(op(memResponseIndex))
                    writeback(memResponseIndex) := atomic
                    valid(memResponseIndex) := atomic
                }
            }
        }

        io.receivedMemResponse.resp.valid := io.receivedMemResponse.req.valid
        io.receivedMemResponse.resp.bits.loadDataIdx := loadDataIdx(memResponseIndex)

        // drive writeback requests
        val writebackIdx = PriorityEncoder(writeback.asUInt)
        io.writebackReq.valid := writeback.orR
        io.writebackReq.bits.token := makeToken(writebackIdx)
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
    val allQueues = globalLoadQueues ++ globalStoreQueues ++ shmemLoadQueues ++ shmemStoreQueues

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

        val globalMemory = lsuQueueReservation.req.bits.addressSpace === AddressSpace.globalMemory
        val sharedMemory = lsuQueueReservation.req.bits.addressSpace === AddressSpace.sharedMemory
        val isLoad = MemOp.isLoad(lsuQueueReservation.req.bits.op)
        val isStore = MemOp.isStore(lsuQueueReservation.req.bits.op)
        val isAtomic = MemOp.isAtomic(lsuQueueReservation.req.bits.op)
        val isFence = MemOp.isFence(lsuQueueReservation.req.bits.op)
        val reqValid = lsuQueueReservation.req.valid

        // atomics and fences both treated like stores, 
        // since current LDQ/STQ design already ensures that stores have aq and rl semantics
        val isSAF = isStore || isAtomic || isFence
        
        lsuQueueReservation.req.ready := MuxCase(false.B, Seq(
            (globalMemory && isLoad) -> !globalLoadQueue.io.full,
            (globalMemory && isSAF) -> !globalStoreQueue.io.full,
            (sharedMemory && isLoad) -> !shmemLoadQueue.io.full,
            (sharedMemory && isSAF) -> !shmemStoreQueue.io.full
        ))

        val token = Wire(new LsuQueueToken)
        token.warpId := warp.U
        token.addressSpace := lsuQueueReservation.req.bits.addressSpace
        token.ldq := isLoad
        token.index := MuxCase(DontCare, Seq(
            (globalMemory && isLoad) -> idxBits(globalLoadQueue.io.myTail),
            (globalMemory && isSAF) -> idxBits(globalStoreQueue.io.myTail),
            (sharedMemory && isLoad) -> idxBits(shmemLoadQueue.io.myTail),
            (sharedMemory && isSAF) -> idxBits(shmemStoreQueue.io.myTail)
        ))
        lsuQueueReservation.resp.valid := lsuQueueReservation.req.fire
        lsuQueueReservation.resp.bits.token := token

        globalLoadQueue.io.enqueue  := lsuQueueReservation.req.fire && globalMemory && isLoad
        globalStoreQueue.io.enqueue := lsuQueueReservation.req.fire && globalMemory && isSAF
        shmemLoadQueue.io.enqueue   := lsuQueueReservation.req.fire && sharedMemory && isLoad
        shmemStoreQueue.io.enqueue  := lsuQueueReservation.req.fire && sharedMemory && isSAF
        
        for (queue <- Seq(globalLoadQueue, globalStoreQueue, shmemLoadQueue, shmemStoreQueue)) {
            queue.io.op := lsuQueueReservation.req.bits.op
            queue.io.addressIdx := lsuQueueReservation.req.bits.addressIdx
            queue.io.storeDataIdx := lsuQueueReservation.req.bits.storeDataIdx
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
    val memReqArbiter = Module(new Arbiter(new LSQReq, nGlobalReqs + nShmemReqs))

    io.sendMemRequest.req :<>= memReqArbiter.io.out

    (memReqArbiter.io.in zip (shmemLoadQueues ++ shmemStoreQueues ++ globalLoadQueues ++ globalStoreQueues)).foreach(x => {
        val (arbPort, queue) = x
        arbPort :<>= queue.io.sendMemRequest.req
    })

    for (queue <- allQueues) {
        queue.io.sendMemRequest.resp := io.sendMemRequest.resp
    }

    def tokenMatchesQueue(token: LsuQueueToken, queue: PerWarpQueue): Bool = {
        (token.warpId === queue.warpId.U) &&
        (token.addressSpace === queue.addressSpace.toChisel) &&
        (token.ldq === queue.loadQueue.B)
    }

    // fanout request channel of receivedOperands, receivedMemUpdate, receivedMemResponse to all queues
    for (queue <- allQueues) {
        queue.io.receivedOperands.req := io.receivedOperands.req
        queue.io.receivedMemUpdate := io.receivedMemUpdate
        queue.io.receivedMemResponse.req := io.receivedMemResponse.req

        queue.io.receivedOperands.req.valid := io.receivedOperands.req.valid && tokenMatchesQueue(io.receivedOperands.req.bits.token, queue)
        queue.io.receivedMemUpdate.valid := io.receivedMemUpdate.valid && tokenMatchesQueue(io.receivedMemUpdate.bits.token, queue)
        queue.io.receivedMemResponse.req.valid := io.receivedMemResponse.req.valid && tokenMatchesQueue(io.receivedMemResponse.req.bits.token, queue)
    }

    // collect response channel of receivedOperands, receivedMemResponse from all queues
    val receivedOperandsTokenMatchesQueue = allQueues.map(q => tokenMatchesQueue(io.receivedOperands.req.bits.token, q))
    val receivedMemResponseTokenMatchesQueue = allQueues.map(q => tokenMatchesQueue(io.receivedMemResponse.req.bits.token, q))
    io.receivedOperands.resp := Mux1H(receivedOperandsTokenMatchesQueue, allQueues.map(_.io.receivedOperands.resp))
    io.receivedMemResponse.resp := Mux1H(receivedMemResponseTokenMatchesQueue, allQueues.map(_.io.receivedMemResponse.resp))


    // arbitrate writeback request between queues
    // currently: shared loads > shared atomics > global loads > global atomics
    // TODO: better arbitration?
    val nWritebackReqs = muonParams.numWarps * 4 // 4 queues per warp
    val writebackArbiter = Module(new Arbiter(new LSQWritebackReq, nWritebackReqs))
    io.writebackReq :<>= writebackArbiter.io.out

    (writebackArbiter.io.in zip (shmemLoadQueues ++ shmemStoreQueues ++ globalLoadQueues ++ globalStoreQueues)).foreach(x => {
        val (arbPort, queue) = x
        arbPort :<>= queue.io.writebackReq
    })
}

// Wrappers for LSQ reservation interface, injects store and address SRAM indices
class LsuReservationReq(implicit p: Parameters) extends CoreBundle {
    val addressSpace = AddressSpace()
    val op = MemOp()
}

class LsuReservationResp(implicit p: Parameters) extends CoreBundle {
    val token = new LsuQueueToken
}

// Execute interface
class LsuRequest(implicit p: Parameters) extends CoreBundle {
    val token = new LsuQueueToken

    val tmask = Vec(muonParams.numLanes, Bool())
    val address = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
    val imm = UInt(muonParams.archLen.W)

    val destReg = UInt(muonParams.pRegBits.W)
    val storeData = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
}

// Writeback interface to rest of core
class LsuResponse(implicit p: Parameters) extends CoreBundle {
    val warpId = UInt(muonParams.warpIdBits.W)

    val packet = UInt(lsuDerived.packetBits.W)
    val tmask = Vec(muonParams.numLanes, Bool())
    val destReg = UInt(muonParams.pRegBits.W)
    val writebackData = Vec(muonParams.lsu.numLsuLanes, UInt(muonParams.archLen.W))
}

/* 
# Downstream memory interface

The LSU Memory Request interface is per-warp with separate data / address / tmask per lane, 
but the tag is shared across all lanes.

The core's memory interface is fully per-LSU-lane, with a per-LSU-lane tag as well. Generally, for coalesced requests, 
the responses will come back together, but for uncoalesced requests, no such guarantee is made. As such, we
need to support partial writes into the load data staging SRAM, and we need to keep track of which words in a row
are valid, only advancing the state machine to begin writing back once all of them are.

As such, we need to convert from LSU memory request to core memory request by appending LSU lane id, 
and the LSU Memory Response interface should support per-LSU-lane valids. 
We also need to convert from core memory response to LSU memory response(s). This is done very naively, 
by picking the first valid lane on the core side, and filtering only those responses whose tag (excluding lane id) 
matches it. 

In the future, it may be possible to begin writing back to register files once a packet is ready (or even
individual lanes within a packet), rather than the full warp
*/
class LsuMemTag(implicit p: Parameters) extends CoreBundle {
    val token = new LsuQueueToken
    val packet = UInt(lsuDerived.packetBits.W)
}

class LsuMemRequest(implicit p: Parameters) extends CoreBundle {
    val tag = UInt(lsuDerived.sourceIdBits.W)
    
    val op = MemOp()
    val address = Vec(muonParams.lsu.numLsuLanes, UInt(muonParams.archLen.W))
    val data = Vec(muonParams.lsu.numLsuLanes, UInt(muonParams.archLen.W))
    val tmask = Vec(muonParams.lsu.numLsuLanes, Bool())
}

class LsuMemResponse(implicit p: Parameters) extends CoreBundle {
    val tag = UInt(lsuDerived.sourceIdBits.W)

    val valid = Vec(muonParams.lsu.numLsuLanes, Bool())
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
}

class LoadStoreUnit(implicit p: Parameters) extends CoreModule()(p) {

    val io = IO(new Bundle {
        val coreReservations = Vec(muonParams.numWarps, new Bundle {
            val req = Flipped(Decoupled(new LsuReservationReq))
            val resp = Valid(new LsuReservationResp)
        })
        val coreReq = Flipped(Decoupled(new LsuRequest))
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
    val loadDataValidTy = Vec(muonParams.lsu.loadDataEntries * lsuDerived.numPackets, Vec(muonParams.lsu.numLsuLanes, Bool()))
    val loadDataValid = RegInit(loadDataValidTy, 0.U.asTypeOf(loadDataValidTy))

    val totalQueueEntries = muonParams.numWarps * (muonParams.lsu.numGlobalLdqEntries + muonParams.lsu.numGlobalStqEntries + muonParams.lsu.numSharedLdqEntries + muonParams.lsu.numSharedStqEntries)
    val pRegTmaskMem = SyncReadMem(totalQueueEntries, new PRegTmask)

    // -- Handle reservations from core --

    // arbitrate between reservations, injecting allocated address SRAM index
    // and allocated store data index (for stores / atomics)
    // TODO: better arbitration? right now lower warp IDs have priority, even if they block higher warps
    // due to lack of free slots in SRAM allocator
    addressTmaskAllocator.io.allocate := false.B
    storeDataAllocator.io.allocate := false.B

    for (warp <- 0 until muonParams.numWarps) {
        val queueReservation = loadStoreQueues.io.queueReservations(warp)
        val coreReservation = io.coreReservations(warp)

        queueReservation.req.bits.addressSpace := coreReservation.req.bits.addressSpace
        queueReservation.req.bits.op := coreReservation.req.bits.op

        coreReservation.resp := queueReservation.resp
    }

    val coreReservationValids = Cat(io.coreReservations.map(r => r.req.valid).reverse)
    val queueReservationReadys = Cat(loadStoreQueues.io.queueReservations.map(r => r.req.ready).reverse)
    val reservationFireOH = PriorityEncoderOH(coreReservationValids & queueReservationReadys)
    
    for (warp <- 0 until muonParams.numWarps) {
        val queueReservation = loadStoreQueues.io.queueReservations(warp)
        val coreReservation = io.coreReservations(warp)
        val attemptReserve = reservationFireOH(warp)

        when (attemptReserve) {
            // fences don't need a slot in store data, despite being kept in store queue
            // stores and atomics require both a store data slot and address/tmask slot
            // other memory ops only need address/tmask slot
            when (MemOp.isFence(coreReservation.req.bits.op)) {
                coreReservation.req.ready := true.B

                queueReservation.req.valid := true.B
                queueReservation.req.bits.addressIdx := DontCare
                queueReservation.req.bits.storeDataIdx := DontCare
            }.elsewhen(MemOp.isStore(coreReservation.req.bits.op) || MemOp.isAtomic(coreReservation.req.bits.op)) {
                addressTmaskAllocator.io.allocate := true.B
                storeDataAllocator.io.allocate := true.B
                coreReservation.req.ready := addressTmaskAllocator.io.allocationValid && storeDataAllocator.io.allocationValid

                queueReservation.req.valid := addressTmaskAllocator.io.allocationValid && storeDataAllocator.io.allocationValid
                queueReservation.req.bits.addressIdx := addressTmaskAllocator.io.allocatedIndex
                queueReservation.req.bits.storeDataIdx := storeDataAllocator.io.allocatedIndex
            }.otherwise {
                addressTmaskAllocator.io.allocate := true.B
                coreReservation.req.ready := addressTmaskAllocator.io.allocationValid

                queueReservation.req.valid := addressTmaskAllocator.io.allocationValid
                queueReservation.req.bits.addressIdx := addressTmaskAllocator.io.allocatedIndex
                queueReservation.req.bits.storeDataIdx := DontCare
            }
        }.otherwise {
            // Not selected by arbitration or queue not ready: do not accept the reservation
            coreReservation.req.ready := false.B

            queueReservation.req.valid := false.B
            queueReservation.req.bits.addressIdx := DontCare
            queueReservation.req.bits.storeDataIdx := DontCare
        }
    }

    // -- Accept operands from reservation station --
    
    // by design, we are always ready to accept operands from reservation station 
    io.coreReq.ready := true.B

    val queueReceivedOperands = loadStoreQueues.io.receivedOperands
    queueReceivedOperands.req.valid := io.coreReq.valid
    queueReceivedOperands.req.bits.token := io.coreReq.bits.token

    when (io.coreReq.fire) {
        val addressTmask = Wire(new AddressTmask)
        
        // address generation
        val imm = io.coreReq.bits.imm
        val address = VecInit(io.coreReq.bits.address.map(_ + imm))
        addressTmask.address := address
        addressTmask.tmask := io.coreReq.bits.tmask

        addressTmaskMem.write(queueReceivedOperands.resp.bits.addressIdx, addressTmask)
        storeDataMem.write(queueReceivedOperands.resp.bits.storeDataIdx, io.coreReq.bits.storeData)

        val pRegTmaskWriteIdx = tokenToPRegTmaskIndex(io.coreReq.bits.token)
        val pRegTmask = Wire(new PRegTmask)
        pRegTmask.destReg := io.coreReq.bits.destReg
        pRegTmask.tmask := io.coreReq.bits.tmask
        
        pRegTmaskMem.write(pRegTmaskWriteIdx, pRegTmask)
    }

    // -- Generate downstream memory requests -- 

    val addressTmaskReadIdx = Wire(UInt(muonParams.lsu.addressIdxBits.W))
    val storeDataReadIdx = Wire(UInt(muonParams.lsu.storeDataIdxBits.W))
    val addressTmask = addressTmaskMem.read(addressTmaskReadIdx)
    val storeData = storeDataMem.read(storeDataReadIdx)
    
    class MemRequestGen extends CoreModule {
        val io = IO(new Bundle {
            val queueRequest = Flipped(Decoupled(new LSQReq))
            val queueResponse = Valid(new LSQResp)

            val allocateLoadData = Output(Bool())
            val allocatedLoadDataIndex = Input(UInt(muonParams.lsu.loadDataIdxBits.W))
            val allocatorHasFree = Input(Bool())

            val deallocateStoreData = Output(Bool())
            val deallocateStoreDataIndex = Output(UInt(muonParams.lsu.storeDataIdxBits.W))
            val deallocateAddressTmask = Output(Bool())
            val deallocateAddressTmaskIndex = Output(UInt(muonParams.lsu.addressIdxBits.W))

            val memRequest = Decoupled(new LsuMemRequest)
            val addressSpace = Output(AddressSpace())

            val addressTmaskReadIdx = Output(UInt(muonParams.lsu.addressIdxBits.W))
            val addressTmask = Input(new AddressTmask)

            val storeDataReadIdx = Output(UInt(muonParams.lsu.storeDataIdxBits.W))
            val storeData = Input(Vec(muonParams.numLanes, UInt(muonParams.archLen.W)))
        })

        val valid = RegInit(false.B)
        val packet = RegInit(0.U(lsuDerived.packetBits.W))
        val finalPacket = (packet === (lsuDerived.numPackets - 1).U)
        
        val op = RegInit(MemOp.loadWord)
        val token = RegInit(0.U.asTypeOf(new LsuQueueToken))

        io.addressTmaskReadIdx := io.queueRequest.bits.addressIdx
        io.storeDataReadIdx := io.queueRequest.bits.storeDataIdx
        
        when (io.memRequest.fire) {
            packet := packet + 1.U
            when (finalPacket) {
                valid := false.B
            }
        }

        // either there is no request currently, or its about to finish
        // also need to ensure that if the incoming request needs load data allocation, we can provide it
        val incomingQueueRequestNeedsLoadData = MemOp.isLoad(io.queueRequest.bits.op) || MemOp.isAtomic(io.queueRequest.bits.op)
        io.allocateLoadData := incomingQueueRequestNeedsLoadData && io.queueRequest.fire
        io.queueRequest.ready := (!valid || (finalPacket && io.memRequest.fire)) && (!incomingQueueRequestNeedsLoadData || io.allocatorHasFree)
        io.queueResponse.valid := io.queueRequest.fire
        io.queueResponse.bits.loadDataIdx := io.allocatedLoadDataIndex

        io.deallocateStoreData := false.B
        io.deallocateStoreDataIndex := DontCare
        io.deallocateAddressTmask := false.B
        io.deallocateAddressTmaskIndex := DontCare

        when (io.queueRequest.fire) {
            valid := true.B
            packet := 0.U
            op := io.queueRequest.bits.op
            token := io.queueRequest.bits.token

            io.deallocateAddressTmask := true.B
            io.deallocateAddressTmaskIndex := io.queueRequest.bits.addressIdx
            when (MemOp.isStore(io.queueRequest.bits.op) || MemOp.isAtomic(io.queueRequest.bits.op)) {
                io.deallocateStoreData := true.B
                io.deallocateStoreDataIndex := io.queueRequest.bits.storeDataIdx
            }
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

        io.addressSpace := token.addressSpace
    }

    // TODO: utilize both memory interfaces in parallel?
    val reqGen = Module(new MemRequestGen)

    reqGen.io.queueRequest :<>= loadStoreQueues.io.sendMemRequest.req
    loadStoreQueues.io.sendMemRequest.resp := reqGen.io.queueResponse
    
    // allocate load data index, deallocate store data index and address/tmask index
    loadDataAllocator.io.allocate := reqGen.io.allocateLoadData
    reqGen.io.allocatedLoadDataIndex := loadDataAllocator.io.allocatedIndex
    reqGen.io.allocatorHasFree := loadDataAllocator.io.hasFree

    storeDataAllocator.io.deallocate := reqGen.io.deallocateStoreData
    storeDataAllocator.io.deallocateIndex := reqGen.io.deallocateStoreDataIndex
    addressTmaskAllocator.io.deallocate := reqGen.io.deallocateAddressTmask
    addressTmaskAllocator.io.deallocateIndex := reqGen.io.deallocateAddressTmaskIndex
    
    // route to correct downstream interface
    io.globalMemReq.bits := reqGen.io.memRequest.bits
    io.shmemReq.bits := reqGen.io.memRequest.bits
    
    when (reqGen.io.addressSpace === AddressSpace.globalMemory) {
        io.globalMemReq.valid := reqGen.io.memRequest.valid
        io.shmemReq.valid := false.B
        reqGen.io.memRequest.ready := io.globalMemReq.ready
    }.otherwise {
        io.globalMemReq.valid := reqGen.io.memRequest.valid
        io.shmemReq.valid := true.B
        reqGen.io.memRequest.ready := io.shmemReq.ready
    }
    
    storeDataReadIdx := reqGen.io.storeDataReadIdx
    addressTmaskReadIdx := reqGen.io.addressTmaskReadIdx
    reqGen.io.addressTmask := addressTmask
    reqGen.io.storeData := storeData
    
    // -- Memory Update --
    
    // for now, we don't use this at all
    loadStoreQueues.io.receivedMemUpdate.valid := false.B
    loadStoreQueues.io.receivedMemUpdate.bits := DontCare

    // -- Receive memory responses --
    {
        // arbitration: responses from global > responses from shared
        val respValids = Cat(io.shmemResp.valid, io.globalMemResp.valid)
        val readys = PriorityEncoderOH(respValids)
        io.globalMemResp.ready := readys(0)
        io.shmemResp.ready := readys(1)

        val receivedResp = respValids.orR

        val respTagBits = Mux1H(respValids, Seq(
            io.globalMemResp.bits.tag,
            io.shmemResp.bits.tag
        ))
        
        val respTag = respTagBits.asTypeOf(new LsuMemTag)
        
        // packet forms LSBs of index
        val loadDataWriteIdx = Wire(UInt(lsuDerived.loadDataPhysicalIdxBits.W))
        loadDataWriteIdx := (loadStoreQueues.io.receivedMemResponse.resp.bits.loadDataIdx * lsuDerived.numPackets.U) + respTag.packet
        
        val loadDataWriteVal = Mux1H(respValids, Seq(
            io.globalMemResp.bits.data,
            io.shmemResp.bits.data
        ))
        val loadDataValids = Mux1H(respValids, Seq(
            io.globalMemResp.bits.valid,
            io.shmemResp.bits.valid
        ))

        val loadDataValidNext = loadDataValid(loadDataWriteIdx) | loadDataValids

        when (receivedResp.orR) {
            when (loadDataValidNext.andR) {
                loadDataValid(loadDataWriteIdx) := 0.U.asTypeOf(loadDataValid(loadDataWriteIdx))
            }.otherwise {
                loadDataValid(loadDataWriteIdx) := loadDataValidNext
            }
            
            loadDataMem.write(loadDataWriteIdx, loadDataWriteVal, loadDataValids)
        }

        loadStoreQueues.io.receivedMemResponse.req.valid := respValids.orR && loadDataValidNext.andR
        loadStoreQueues.io.receivedMemResponse.req.bits.token := respTag.token
    }
    
    // -- Writeback --
    class Writeback extends CoreModule {
        val io = IO(new Bundle {
            val writebackReq = Flipped(Decoupled(new LSQWritebackReq))
            val coreResp = Decoupled(new LsuResponse)

            val loadDataReadIdx = Output(UInt(lsuDerived.loadDataPhysicalIdxBits.W))
            val loadDataReadVal = Input(Vec(muonParams.lsu.numLsuLanes, UInt(muonParams.archLen.W)))

            val pRegTmaskReadIdx = Output(new LsuQueueToken)
            val pRegTmaskReadVal = Input(new PRegTmask)

            val deallocateLoadData = Output(Bool())
            val deallocateLoadDataIdx = Output(UInt(muonParams.lsu.loadDataIdxBits.W))
        })

        io.deallocateLoadData := false.B
        io.deallocateLoadDataIdx := DontCare

        // Writeback state machine
        
        // stage 1: accept writeback request, initiate first read to data SRAM, initiate read from metadata SRAM
        // stage 2: read from SRAM, drive core writeback interface
        val s1_valid = RegInit(false.B)
        val s1_ready = Wire(Bool())
        val s1_req = RegInit(0.U.asTypeOf(new LSQWritebackReq))
        
        val s2_valid = RegInit(false.B)
        val s2_ready = Wire(Bool())
        val s2_req = RegInit(0.U.asTypeOf(new LSQWritebackReq))
        val s2_packet = RegInit(0.U(lsuDerived.packetBits.W))
        val s2_metadata = RegInit(0.U.asTypeOf(new PRegTmask))
        val finalPacket = Wire(Bool())
        finalPacket := (s2_packet + 1.U) === lsuDerived.numPackets.U

        io.writebackReq.ready := s1_ready
        s1_ready := !s1_valid || (s1_valid && s2_ready)

        when (s1_valid && s2_ready) {
            s1_valid := false.B
        }
        
        when (io.writebackReq.fire) {
            s1_valid := true.B
            s1_req := io.writebackReq.bits
        }
    
        io.loadDataReadIdx := Mux(
            s2_ready,
            s1_req.loadDataIdx * lsuDerived.numPackets.U,
            s2_req.loadDataIdx * lsuDerived.numPackets.U + s2_packet + 1.U
        )
        
        io.pRegTmaskReadIdx := s1_req.token

        when (io.coreResp.fire) {
            s2_packet := s2_packet + 1.U
        }

        s2_ready := !s2_valid || (s2_valid && finalPacket && io.coreResp.fire)

        when (s2_valid && finalPacket && io.coreResp.fire) {
            s2_valid := false.B
            io.deallocateLoadData := true.B
            io.deallocateLoadDataIdx := s2_req.loadDataIdx
        }

        when (s1_valid && s2_ready) {
            s2_valid := true.B
            s2_req := s1_req
            s2_packet := 0.U
            s2_metadata := io.pRegTmaskReadVal
        }

        io.coreResp.valid := s2_valid
        io.coreResp.bits.tmask := s2_metadata.tmask
        io.coreResp.bits.destReg := s2_metadata.destReg
        io.coreResp.bits.writebackData := io.loadDataReadVal
        io.coreResp.bits.warpId := s2_req.token.warpId
        io.coreResp.bits.packet := s2_packet
    }

    val writeback = Module(new Writeback)
    io.coreResp :<>= writeback.io.coreResp
    writeback.io.writebackReq :<>= loadStoreQueues.io.writebackReq
    
    loadDataAllocator.io.deallocate := writeback.io.deallocateLoadData
    loadDataAllocator.io.deallocateIndex := writeback.io.deallocateLoadDataIdx

    writeback.io.loadDataReadVal := loadDataMem.read(writeback.io.loadDataReadIdx)
    writeback.io.pRegTmaskReadVal := pRegTmaskMem.read(
        tokenToPRegTmaskIndex(writeback.io.pRegTmaskReadIdx)
    )
}

// See [Downstream memory interface]
class LSUCoreAdapter(implicit p: Parameters) extends CoreModule()(p) {
    val io = IO(new Bundle {
        val lsu = new Bundle {
            val globalMemReq = Decoupled(new LsuMemRequest)
            val globalMemResp = Flipped(Decoupled(new LsuMemResponse))

            val shmemReq = Decoupled(new LsuMemRequest)
            val shmemResp = Flipped(Decoupled(new LsuMemResponse))
        }

        val core = new Bundle {
            val dmem = new DataMemIO
            val smem = new SharedMemIO
        }
    })

    def lsuTagToCoreTag = (lsuTag: UInt, lane: Int) => {
        lsuTag * muonParams.numLanes.U + lane.U
    }

    def coreTagToLsuTag = (coreTag: UInt) => {
        coreTag >> log2Ceil(muonParams.numLanes).U
    }

    def connectReq = (lsuReq: DecoupledIO[LsuMemRequest], coreReq: Vec[DecoupledIO[MemRequest[Bundle]]]) => {
        val readys = coreReq.map(_.ready)
        val allReady = readys.reduce(_ && _)
        for ((lane, laneId) <- coreReq.zipWithIndex) {
            lane.valid := allReady && lsuReq.valid && lsuReq.bits.tmask(laneId)
            lane.bits.tag := lsuTagToCoreTag(lsuReq.bits.tag, laneId)
            lane.bits.address := lsuReq.bits.address(laneId)
            lane.bits.data := lsuReq.bits.data(laneId)
            lane.bits.mask := ???
            lane.bits.metadata := DontCare
            lane.bits.size := ???
            lane.bits.store := MemOp.isStore(lsuReq.bits.op)
        }
        lsuReq.ready := allReady
    }

    def connectResp = (lsuResp: DecoupledIO[LsuMemResponse], coreResp: Vec[DecoupledIO[MemResponse[Bundle]]]) => {
        val respValids = coreResp.map(_.valid)
        val lsuTags = VecInit(coreResp.map(r => coreTagToLsuTag(r.bits.tag)))
        val leader = PriorityEncoder(respValids)
        val leaderLsuTag = lsuTags(leader)
        val matchesLeader = VecInit(lsuTags.map(t => t === leaderLsuTag))
        val respData = VecInit(coreResp.map(_.bits.data))

        lsuResp.valid := respValids.orR
        lsuResp.bits.tag := leaderLsuTag
        lsuResp.bits.valid := matchesLeader
        lsuResp.bits.data := respData
        
        for ((lane, laneId) <- coreResp.zipWithIndex) {
            lane.ready := lsuResp.ready && matchesLeader(laneId)
        }
    }

    connectReq(io.lsu.globalMemReq, io.core.dmem.req)
    connectReq(io.lsu.shmemReq, io.core.smem.req)
    connectResp(io.lsu.globalMemResp, io.core.dmem.resp)
    connectResp(io.lsu.shmemResp, io.core.smem.resp)
}