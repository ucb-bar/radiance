// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.unittest

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.tile.TileKey
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy.{IdRange, AddressSet}
import freechips.rocketchip.unittest.UnitTest
import radiance.muon._
import radiance.subsystem.DummyTileParams
import scala.collection.mutable.ArrayBuffer

/** Testbench for Muon with the test signals */
class MuonCoreTestbench(implicit p: Parameters) extends LazyModule {
  val coreTop = LazyModule(new MuonCoreTop()(p.alterMap(Map(
    // @cleanup: this should be unnecessary with WithMuonCores(headless = true)
    TileKey -> DummyTileParams
  ))))

  lazy val module = new MuonCoreTestbenchImp
  class MuonCoreTestbenchImp extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val finished = Bool()
    })

    io.finished := coreTop.module.io.finished
  }
}

/** Testbench for frontend-as-top co-sim config */
class MuonFrontendTestbench(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val finished = Bool()
  })

  val m = p(MuonKey)

  val fe = Module(new Frontend()(p))
  val cbe = Module(new CyclotronBackendBlackBox)

  cbe.io.clock := clock
  cbe.io.reset := reset.asBool

  // fe csr, hartid
  fe.io.softReset := false.B

  // reply with dummy LSU tokens; Cyclotron model does not depend on their
  // values
  fe.io.lsuReserve.foreach { warpRes =>
    warpRes.req.ready := true.B
    warpRes.resp.valid := true.B
    warpRes.resp.bits := 0.U.asTypeOf(new LsuReservationResp)
  }

  // fe decode -> cyclotron back end
  // note issue logic is simple pass-through of decode
  (cbe.io.issue zip fe.io.ibuf).foreach { case (b, f) =>
    b.bits.fromUop(f.bits)
    b.valid := f.valid
    f.ready := b.ready
  }

  // cyclotron back end -> fe commit
  fe.io.commit := cbe.io.commit

  // fe imem <> cyclotron back end
  // TODO: imem being in cbe is weird
  cbe.io.imem.req <> fe.io.imem.req
  fe.io.imem.resp <> cbe.io.imem.resp

  io.finished := cbe.io.finished

  dontTouch(fe.io)
}

/** Testbench for backend-as-top co-sim config */
class MuonBackendTestbench(implicit val p: Parameters) extends Module with HasCoreParameters {
  val io = IO(new Bundle {
    val finished = Bool()
  })

  val ibuf = Module(new InstBuffer)
  val cfe = Module(new CyclotronFrontend()(p))
  val cdiff = Module(new CyclotronDiffTest(tick = false)(p))
  val be = Module(new Backend(muonParams.difftest)(p.alterMap(Map(
    TileKey -> DummyTileParams
  ))))

  // imem in the ISA model is not used
  cfe.io.imem.req.valid := false.B
  cfe.io.imem.req.bits := DontCare
  cfe.io.imem.resp.ready := false.B
  cdiff.io.trace <> be.io.trace.get

  // cfe -> ibuf enq
  (ibuf.io.enq zip cfe.io.ibuf).foreach { case (ib, cf) =>
    // workaround of ibufEnqIO not being a DecoupledIO
    val ibFull = (ib.count === muonParams.ibufDepth.U)
    ib.uop.valid := cf.valid && !ibFull
    cf.ready := !ibFull
    ib.uop.bits := cf.bits.toUop()
  }

  be.io.dmem.resp.foreach(_.valid := false.B)
  be.io.dmem.resp.foreach(_.bits := DontCare)
  be.io.dmem.req.foreach(_.ready := false.B)
  be.io.smem.resp.foreach(_.valid := false.B)
  be.io.smem.resp.foreach(_.bits := DontCare)
  be.io.smem.req.foreach(_.ready := false.B)
  // single-core
  be.io.clusterId := 0.U
  be.io.coreId := 0.U
  be.io.softReset := false.B
  be.io.feCSR := 0.U.asTypeOf(be.io.feCSR)
  be.io.flush.i.done := true.B
  be.io.flush.d.done := true.B
  // TODO: barriers not handled
  be.io.barrier.req.ready := false.B
  be.io.barrier.resp.valid := false.B
  be.io.barrier.resp.bits := DontCare

  // ibuf -> be
  be.io.ibuf <> ibuf.io.deq
  ibuf.io.lsuReserve <> be.io.lsuReserve
  dontTouch(be.io)

  // run until all ibufs dry up
  val ibufDry = !be.io.ibuf.map(_.valid).reduce(_ || _)
  io.finished := cfe.io.finished && ibufDry
}

/** Testbench for LSU-as-top config */
class MuonLSUTestbench(implicit p: Parameters) extends LazyModule {
  val lsuWrapper = LazyModule(new LSUWrapper()(p))
  val xbar = TLXbar()
  val fakeGmem = TLRAM(
    address = AddressSet(0x0, 1024*1024*16-1), // TODO: don't hardcode; 1MB region for each warp
    beatBytes = p(MuonKey).archLen / 8
  )

  // we need a buffer in between because lsu expects atomic (all-lanes-at-once) requests to
  // downstream memory interface - checks all ready before setting valid; arbitration in xbar
  // leads to combinational loop if there is no buffer between. this is not compliant
  // with TL standard (which states valid should never depend on ready), but should probably
  // be safe.
  for (lane <- 0 until p(MuonKey).numLanes) {
    xbar := TLBuffer() := lsuWrapper.lsuNodes(lane)
  }
  fakeGmem := xbar

  lazy val module = new MuonLSUTestbenchImp
  class MuonLSUTestbenchImp extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val finished = Bool()
    })

    val stimulus = Module(new SynthesizableStimulus()(p))

    // connect stimulus to lsu wrapper
    lsuWrapper.module.io <> stimulus.io

    io.finished := stimulus.done
  }
}

/** DUT module for core-standalone testbench.
 *  Hooks up a MuonCore with a Rust instruction memory model, and exposes a TL
 *  node for its global memory interface. */
class MuonCoreTop(implicit p: Parameters) extends LazyModule with HasCoreParameters {
  val sourceIdsPerLane = 1 << lsuDerived.sourceIdBits

  // every core lane is a separate TL client
  val lsuNodes = Seq.tabulate(muonParams.lsu.numLsuLanes) { lane =>
    TLClientNode(Seq(
      TLMasterPortParameters.v1(
        Seq(TLMasterParameters.v1(
          name = f"lsu-gmem-lane$lane",
          sourceId = IdRange(0, sourceIdsPerLane),
          requestFifo = false
        ))
      )
    ))
  }

  lazy val module = new MuonCoreTopImpl
  class MuonCoreTopImpl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val finished = Output(Bool())
    })

    val core = Module(new MuonCore()(p))
    val imem = Module(new CyclotronInstMem)
    val dmem = Module(new CyclotronDataMem)
    core.io.imem <> imem.io.imem
    core.io.dmem <> dmem.io.dmem

    // tie off shared mem
    core.io.smem.req.foreach(_.ready := false.B)
    core.io.smem.resp.foreach(_.valid := false.B)
    core.io.smem.resp.foreach(_.bits := DontCare)

    // tie off other IO
    core.io.barrier.req.ready := false.B
    core.io.barrier.resp.valid := false.B
    core.io.barrier.resp.bits := DontCare
    core.io.flush.i.done := true.B
    core.io.flush.d.done := true.B
    core.io.softReset := false.B
    core.io.coreId := 0.U
    core.io.clusterId := 0.U

    // performance counters
    val cperf = Module(new Profiler)
    cperf.io.perf <> core.io.perf
    cperf.io.finished := core.io.finished

    // RTL/model diff-test
    if (core.muonParams.difftest) {
      val cdiff = Module(new CyclotronDiffTest(tick = true))
      cdiff.io.trace <> core.io.trace.get
    }

    io.finished := core.io.finished
  }
}

/** DUT module for LSU testbench */
class LSUWrapper(implicit p: Parameters) extends LazyModule with HasCoreParameters {
  val sourceIdsPerLane = 1 << lsuDerived.sourceIdBits

  // every lsu lane is a separate TL client
  def masterParams = (lane: Int) => {
    val sourceId = IdRange(
      0,
      sourceIdsPerLane
    )

    TLMasterParameters.v1(
      name = f"lsu-gmem-lane$lane",
      sourceId = sourceId,
      requestFifo = false
    )
  }

  val lsuNodes = Seq.tabulate(muonParams.lsu.numLsuLanes) {lane =>
    TLClientNode(Seq(
      TLMasterPortParameters.v1(
        Seq(masterParams(lane))
      )
    ))
  }

  lazy val module = new LSUWrapperImpl
  class LSUWrapperImpl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val coreReservations = Vec(muonParams.numWarps, new Bundle {
          val req = Flipped(Decoupled(new LsuReservationReq))
          val resp = Valid(new LsuReservationResp)
      })
      val coreReq = Flipped(Decoupled(new LsuRequest))
      val coreResp = Decoupled(new LsuResponse)
    })

    val lsu = Module(new LoadStoreUnit()(p))
    val lsuAdapter = Module(new LSUCoreAdapter)

    // lsu <> pipeline
    (io.coreReservations zip lsu.io.coreReservations).foreach {
      case (i, l) => {
        l.req :<>= i.req
        i.resp :<>= l.resp
      }
    }
    lsu.io.coreReq :<>= io.coreReq
    io.coreResp :<>= lsu.io.coreResp

    // lsuAdapter <> lsu
    lsuAdapter.io.lsu.globalMemReq :<>= lsu.io.globalMemReq
    lsu.io.globalMemResp :<>= lsuAdapter.io.lsu.globalMemResp
    lsuAdapter.io.lsu.shmemReq :<>= lsu.io.shmemReq
    lsu.io.shmemResp :<>= lsuAdapter.io.lsu.shmemResp

    // mem <> lsuAdapter
    MuonMemTL.multiConnectTL(
      lsuAdapter.io.core.dmem.req,
      lsuAdapter.io.core.dmem.resp,
      lsuNodes
    )
    // tie off shared mem
    lsuAdapter.io.core.smem.req.foreach(_.ready := false.B)
    lsuAdapter.io.core.smem.resp.foreach(_.valid := false.B)
    lsuAdapter.io.core.smem.resp.foreach(_.bits := DontCare)
  }
}

class SynthesizableStimulus(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val coreReservations = Vec(muonParams.numWarps, new Bundle {
        val req = Decoupled(new LsuReservationReq)
        val resp = Flipped(Valid(new LsuReservationResp))
    })
    val coreReq = Decoupled(new LsuRequest)
    val coreResp = Flipped(Decoupled(new LsuResponse))
  })

  val done = IO(Output(Bool()))

  case class MemoryOperation(
    val warpId: Int,
    val memOp: MemOp.Type,
    val address: Seq[Long],
    val imm: Long,
    val data: Seq[Long],
    val tmask: Seq[Boolean],
    val destReg: Long,

    val reservationAt: Long,
    val operandsDelay: Long,

    val debugId: Long,
    val expectedWriteback: Option[(Int, Seq[Boolean], Seq[Long])] = None,
  )

  def stimulus(seed: Int) = {
    scala.util.Random.setSeed(seed)

    // Generate "program-order" sequence of memory operations
    val memOpsPerWarp = scala.collection.mutable.ArrayBuffer[Seq[MemoryOperation]]()
    var debugId = 0
    for (i <- 0 until muonParams.numWarps) {
      val memOps = scala.collection.mutable.ArrayBuffer[MemoryOperation]()
      val memoryState = scala.collection.mutable.Map[Long, Long]()
      var reservation = scala.util.Random.nextInt(15).toLong
      for (j <- 0 until 500) {
        // random memory operation
        // val r = scala.util.Random.nextDouble()
        // val (memOp, memOpNum) = if (r < 0.5 && memoryState.nonEmpty) {
        //   (MemOp.loadWord, 4)
        // }
        // else {
        //   (MemOp.storeWord, 7)
        // }

        val memOpNum = if (memoryState.isEmpty) {
          7
        }
        else {
          scala.util.Random.nextInt(8)
        }

        val memOp = memOpNum match {
          case 0 => MemOp.loadByte
          case 1 => MemOp.loadByteUnsigned
          case 2 => MemOp.loadHalf
          case 3 => MemOp.loadHalfUnsigned
          case 4 => MemOp.loadWord
          case 5 => MemOp.storeByte
          case 6 => MemOp.storeHalf
          case 7 => MemOp.storeWord
        }

        val isLoad = memOpNum >= 0 && memOpNum < 5
        val isStore = memOpNum >= 5 && memOpNum < 8

        val generateAddresses = () => {
          Seq.fill(muonParams.numLanes) {
            if (isLoad || scala.util.Random.nextDouble() < 0.3 && memoryState.nonEmpty) {
              // 30% chance to repeat an address we've looked at before for stores
              // if it's a load, we require it to be an address we've touched before
              // (otherwise result of reading is totally undefined)
              val keys = memoryState.keys.toSeq
              val randomOffset = memOpNum match {
                case 0 | 1 | 5 => scala.util.Random.nextInt(4)
                case 2 | 3 | 6 => scala.util.Random.nextInt(2) * 2
                case 4 | 7     => 0
              }
              val addr = keys(scala.util.Random.nextInt(keys.length)) + randomOffset
              addr
            } else {
              // random aligned address in 1MB range
              val addr = 1024 * 1024 * i + scala.util.Random.nextInt(1024 * 1024)
              (memOpNum match {
                case 2 | 3 | 6 => addr & ~0x1
                case 4 | 7     => addr & ~0x3
                case _ => addr
              }).toLong
            }
          }
        }

        var address = generateAddresses()

        // stores should have unique addresses, otherwise there is nondeterminism
        // which is hard to test
        while (isStore && address.toSet.size != muonParams.numLanes) {
          address = generateAddresses()
        }

        val destReg = scala.util.Random.nextInt(muonParams.numArchRegs)
        val tmask = Seq.fill(muonParams.numLanes) {
          scala.util.Random.nextBoolean()
        }

        val data = Seq.fill(muonParams.numLanes) {
          scala.util.Random.nextLong(Int.MaxValue.toLong + 1)
        }

        val expectedWriteback = memOpNum match {
          case 0 => {
            // LB
            val data = address.map(a => {
              val word = a & ~0x3
              val offset = (a & 0x3).toInt
              val raw = (memoryState.getOrElse(word, 0L) >> (offset * 8)) & 0xffL
              raw match {
                case b if (b & 0x80) != 0 => (b - 256)
                case b => b
              }
            })
            val bools = tmask
            Some((destReg, bools, data))
          }
          case 1 => {
            // LBU
            val data = address.map(a => {
              val word = a & ~0x3
              val offset = (a & 0x3).toInt
              (memoryState.getOrElse(word, 0L) >> (offset * 8)) & 0xffL
            })
            val bools = tmask
            Some((destReg, bools, data))
          }
          case 2 => {
            // LH
            val data = address.map(a => {
              val word = a & ~0x3
              val offset = (a & 0x2).toInt
              val raw = (memoryState.getOrElse(word, 0L) >> (offset * 8)) & 0xffffL
              raw match {
                case h if (h & 0x8000) != 0 => (h - 65536)
                case h => h
              }
            })
            val bools = tmask
            Some((destReg, bools, data))
          }
          case 3 => {
            // LHU
            val data = address.map(a => {
              val word = a & ~0x3
              val offset = (a & 0x2).toInt
              (memoryState.getOrElse(word, 0L) >> (offset * 8)) & 0xffffL
            })
            val bools = tmask
            Some((destReg, bools, data))
          }
          case 4 => {
            // LW
            val data = address.map(a => memoryState.getOrElse(a, 0L).toLong)
            val bools = tmask
            Some((destReg, bools, data))
          }
          case 5 => {
            // SB
            for (lane <- 0 until muonParams.numLanes) {
              if (tmask(lane)) {
                val word = address(lane) & ~0x3
                val offset = (address(lane) & 0x3).toInt
                // don't mark it in memoryState unless it was already written to by a sw,
                // as the other bits are indeterminate
                if (memoryState.contains(word)) {
                  val oldValue = memoryState(word)

                  val newByte = oldValue & ~(0xffL << (offset * 8)) |
                    (data(lane).toLong & 0xffL) << (offset * 8)
                  memoryState(word) = newByte
                }
              }
            }
            None
          }
          case 6 => {
            // SH
            for (lane <- 0 until muonParams.numLanes) {
              if (tmask(lane)) {
                val word = address(lane) & ~0x3
                val offset = (address(lane) & 0x2).toInt
                if (memoryState.contains(word)) {
                  val oldValue = memoryState(word)

                  val newHalf = oldValue & ~(0xffffL << (offset * 8)) |
                    (data(lane).toLong & 0xffffL) << (offset * 8)
                  memoryState(word) = newHalf
                }
              }
            }
            None
          }
          case 7 => {
            // SW
            for (lane <- 0 until muonParams.numLanes) {
              if (tmask(lane)) {
                val word = address(lane) & ~0x3
                memoryState(word) = data(lane).toLong
              }
            }
            None
          }
        }

        val imm = 0L // unused for now

        memOps += MemoryOperation(
          warpId = i,
          memOp,
          address,
          imm,
          data,
          tmask,
          destReg,
          debugId = debugId,
          reservationAt = reservation,
          operandsDelay = scala.util.Random.nextInt(15).toLong,
          expectedWriteback = expectedWriteback
        )

        debugId += 1
        reservation += scala.util.Random.nextInt(5).toLong
      }
      memOpsPerWarp += memOps.toSeq
    }

    memOpsPerWarp
  }

  def manualStimulus = {
    val perWarpStimulus: ArrayBuffer[Seq[MemoryOperation]] = ArrayBuffer.fill(muonParams.numWarps)(Seq())
    perWarpStimulus.update(0, Seq(
      MemoryOperation(
        warpId = 0,
        memOp = MemOp.storeWord,
        address = Seq.tabulate(muonParams.numLanes)(x => x * 4),
        imm = 0,
        data = Seq.tabulate(muonParams.numLanes)(x => x),
        tmask = Seq.fill(muonParams.numLanes)(true),
        destReg = 0,
        reservationAt = 10,
        operandsDelay = 5,
        debugId = 0,
        expectedWriteback = None
      ),
      MemoryOperation(
        warpId = 0,
        memOp = MemOp.loadWord,
        address = Seq.tabulate(muonParams.numLanes)(x => x * 4),
        imm = 0,
        data = Seq.fill(muonParams.numLanes)(0),
        tmask = Seq.fill(muonParams.numLanes)(true),
        destReg = 5,
        reservationAt = 10,
        operandsDelay = 5,
        debugId = 1,
        expectedWriteback = Some((
          5,
          Seq.fill(muonParams.numLanes)(true),
          Seq.tabulate(muonParams.numLanes)(x => x)
        ))
      )
    ))

    perWarpStimulus
  }

  val scalaStimulus = stimulus(0)

  class StimulusModuleV1(memoryOp: MemoryOperation) extends CoreModule {
    val io = IO(new Bundle {
      val coreReservationReq = Decoupled(new LsuReservationReq)
      val coreReservationResp = Flipped(Valid(new LsuReservationResp))

      val coreReq = Decoupled(new LsuRequest)
      val done = Bool()
    })

    val s_idle :: s_reserve :: s_operandsDelay :: s_issueReq :: s_done :: Nil = Enum(5)
    val state = RegInit(s_idle)
    val token = RegInit(0.U.asTypeOf(new LsuQueueToken))
    val (cycleCounter, _) = Counter(true.B, Int.MaxValue)

    io.coreReservationReq.valid := false.B
    io.coreReservationReq.bits := DontCare

    io.coreReq.valid := false.B
    io.coreReq.bits := DontCare

    switch (state) {
      is(s_idle) {
        when (cycleCounter >= memoryOp.reservationAt.U) {
          state := s_reserve
        }
      }

      is (s_reserve) {
        io.coreReservationReq.valid := true.B
        io.coreReservationReq.bits.addressSpace := AddressSpace.globalMemory
        io.coreReservationReq.bits.op := memoryOp.memOp
        io.coreReservationReq.bits.debugId.get := memoryOp.debugId.U
        when (io.coreReservationReq.fire) {
          token := io.coreReservationResp.bits.token
          state := s_operandsDelay
        }
      }

      is (s_operandsDelay) {
        when (cycleCounter >= (memoryOp.reservationAt + memoryOp.operandsDelay).U) {
          state := s_issueReq
        }
      }

      is (s_issueReq) {
        io.coreReq.valid := true.B
        io.coreReq.bits.op := memoryOp.memOp
        io.coreReq.bits.address := VecInit(memoryOp.address.map(_.U))
        io.coreReq.bits.imm := memoryOp.imm.U
        io.coreReq.bits.storeData := VecInit(memoryOp.data.map(_.U))
        io.coreReq.bits.tmask := VecInit(memoryOp.tmask.map(_.B))
        io.coreReq.bits.destReg := memoryOp.destReg.U
        io.coreReq.bits.token := token

        when (io.coreReq.fire) {
          state := s_done
        }
      }

      is(s_done) {/* nop */}
    }

    io.done := state === s_done
  }

  class SynthesizableMemoryOperation extends Bundle {
    val warpId = UInt(muonParams.warpIdBits.W)
    val memOp = MemOp()
    val address = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
    val imm = UInt(muonParams.archLen.W)
    val data = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
    val tmask = Vec(muonParams.numLanes, Bool())
    val destReg = UInt(muonParams.pRegBits.W)

    val reservationAt = UInt(32.W)
    val operandsDelay = UInt(32.W)

    val debugId = UInt(lsuDerived.debugIdBits.get.W)
  }

  class StimulusModuleV2 extends CoreModule {
    val io = IO(new Bundle {
      val stimulus = Input(new SynthesizableMemoryOperation)

      val coreReservationReq = Decoupled(new LsuReservationReq)
      val coreReservationResp = Flipped(Valid(new LsuReservationResp))

      val coreReq = Decoupled(new LsuRequest)
      val done = Bool()
    })

    val s_idle :: s_reserve :: s_operandsDelay :: s_issueReq :: s_done :: Nil = Enum(5)
    val state = RegInit(s_idle)
    val token = RegInit(0.U.asTypeOf(new LsuQueueToken))
    val (cycleCounter, _) = Counter(true.B, Int.MaxValue)

    io.coreReservationReq.valid := false.B
    io.coreReservationReq.bits := DontCare

    io.coreReq.valid := false.B
    io.coreReq.bits := DontCare

    switch (state) {
      is(s_idle) {
        when (cycleCounter >= io.stimulus.reservationAt) {
          state := s_reserve
        }
      }

      is (s_reserve) {
        io.coreReservationReq.valid := true.B
        io.coreReservationReq.bits.addressSpace := AddressSpace.globalMemory
        io.coreReservationReq.bits.op := io.stimulus.memOp
        io.coreReservationReq.bits.debugId.get := io.stimulus.debugId
        when (io.coreReservationReq.fire) {
          token := io.coreReservationResp.bits.token
          state := s_operandsDelay
        }
      }

      is (s_operandsDelay) {
        when (cycleCounter >= (io.stimulus.reservationAt + io.stimulus.operandsDelay)) {
          state := s_issueReq
        }
      }

      is (s_issueReq) {
        io.coreReq.valid := true.B
        io.coreReq.bits.op := io.stimulus.memOp
        io.coreReq.bits.address := io.stimulus.address
        io.coreReq.bits.imm := io.stimulus.imm
        io.coreReq.bits.storeData := io.stimulus.data
        io.coreReq.bits.tmask := io.stimulus.tmask
        io.coreReq.bits.destReg := io.stimulus.destReg
        io.coreReq.bits.token := token

        when (io.coreReq.fire) {
          state := s_done
        }
      }

      is(s_done) {/* nop */}
    }

    io.done := state === s_done
  }

  class ResponseChecker(implicit p: Parameters) extends CoreModule {
    val io = IO(new Bundle {
      val coreResp = Flipped(Decoupled(new LsuResponse))
      val done = Output(Bool())
    })

    when (io.coreResp.fire) {
      printf(cf"[ResponseChecker] coreResp fire: debugId = ${io.coreResp.bits.debugId.get}, tmask = ${io.coreResp.bits.tmask}, writebackData = ${io.coreResp.bits.writebackData}, warpId = ${io.coreResp.bits.warpId}, destReg = ${io.coreResp.bits.destReg}, packet = ${io.coreResp.bits.packet}\n")
    }

    class ExpectedWritebackBundle extends Bundle {
      val warpId = UInt(muonParams.warpIdBits.W)
      val destReg = UInt(muonParams.pRegBits.W)
      val tmask = Vec(muonParams.numLanes, Bool())
      val data = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
    }
    val expectedWritebackT = new ExpectedWritebackBundle
    val flattenedStimulus = scalaStimulus.flatten.sortInPlaceBy(_.debugId)
    val allMemoryOps = flattenedStimulus.map(
      op => {
        val (destReg, tmask, data) = op.expectedWriteback.getOrElse((
          0,
          Seq.fill(muonParams.numLanes)(false),
          Seq.fill(muonParams.numLanes)(0L),
        ))

        val tmaskLit = tmask.map(_.B)
        val dataLit = data.map(x => {
          if (x < 0) {
            x.S(muonParams.archLen.W).asUInt
          } else {
            x.U(muonParams.archLen.W)
          }
        })

        expectedWritebackT.Lit(
          _.warpId -> op.warpId.U,
          _.destReg -> destReg.U,
          _.tmask -> Vec.Lit(tmaskLit: _*), // cursed scala nonsense to splat a Seq into varargs
          _.data -> Vec.Lit(dataLit: _*)
        )
      }
    )
    val responseROM = VecInit(allMemoryOps.toSeq)
    val gotResponse = RegInit(VecInit(
      flattenedStimulus.map(s => s.expectedWriteback.isEmpty.B).toSeq
    ))

    // collect packets together
    val recomposer = Module(new backend.LaneRecomposer(
      inLanes = lsuDerived.numPackets,
      outLanes = 1,
      elemTypes = Seq(new LsuResponse)
    ))

    io.coreResp.ready := recomposer.io.out.ready
    recomposer.io.in.valid := io.coreResp.valid

    recomposer.io.in.bits.data(0)(0) := io.coreResp.bits

    recomposer.io.out.ready := true.B
    val packets = RegInit(0.U.asTypeOf(recomposer.io.out.bits.data(0)))
    val firePrev = RegNext(recomposer.io.out.fire, false.B)
    when (recomposer.io.out.fire) {
      packets := recomposer.io.out.bits.data(0)
    }

    when (firePrev) {
      val typedPackets = packets.map(x => x.asTypeOf(new LsuResponse))

      // 1. check that debugId, warpId, destReg, tmask are all matching
      typedPackets.reduce( (a, b) => {
        assert(a.debugId.get === b.debugId.get, "Mismatched debugId in recomposed LSU response")
        assert(a.warpId === b.warpId, "Mismatched warpId in recomposed LSU response")
        assert(a.destReg === b.destReg, "Mismatched destReg in recomposed LSU response")
        assert(a.tmask === b.tmask, "Mismatched tmask in recomposed LSU response") // TODO: should tmask be numLsuLanes wide?
        a
      })

      // 2. check that packets are in order
      for (i <- 1 until typedPackets.length) {
        assert(typedPackets(i).packet === i.U, "Out-of-order packets in recomposed LSU response")
      }

      // 3. check data against expected writeback
      val debugId = typedPackets(0).debugId.get
      val expected = responseROM(debugId)

      val warpId = typedPackets(0).warpId
      assert(warpId === expected.warpId, cf"Mismatched warpId in LSU response ($warpId) vs expected (${expected.warpId})")

      val destReg = typedPackets(0).destReg
      assert(destReg === expected.destReg, cf"Mismatched destReg in LSU response ($destReg) vs expected (${expected.destReg})")

      val tmask = typedPackets(0).tmask
      assert(tmask === expected.tmask, cf"Mismatched tmask in LSU response ($tmask) vs expected (${expected.tmask}), debugId = ${debugId}")

      val dataConcat = typedPackets.flatMap(_.writebackData)
      for (i <- 0 until muonParams.numLanes) {
        assert(!tmask(i) || dataConcat(i) === expected.data(i), cf"Mismatched writeback data lane $i in LSU response (${dataConcat(i)}) vs expected ${expected.data(i)})")
      }

      gotResponse(debugId) := true.B
    }

    io.done := gotResponse.reduce(_ && _)
  }

  // val allStimulusModules = scala.collection.mutable.ArrayBuffer[StimulusModuleV2]()
  val allStimulusModules = scala.collection.mutable.ArrayBuffer[StimulusModuleV1]()
  val stimulusBundleT = new SynthesizableMemoryOperation
  for (i <- 0 until muonParams.numWarps) {
    // val stimulusModules = scalaStimulus(i).map(op => Module(new StimulusModuleV2))
    // val stimulusBundles = scalaStimulus(i).map(op => {
    //   val addressLit = op.address.map(_.U(muonParams.archLen.W))
    //   val dataLit = op.data.map(_.U(muonParams.archLen.W))
    //   val tmaskLit = op.tmask.map(_.B)

    //   stimulusBundleT.Lit(
    //     _.warpId -> op.warpId.U(muonParams.warpIdBits.W),
    //     _.memOp -> op.memOp,
    //     _.address -> Vec.Lit(addressLit: _*),
    //     _.imm -> op.imm.U(muonParams.archLen.W),
    //     _.data -> Vec.Lit(dataLit: _*),
    //     _.tmask -> Vec.Lit(tmaskLit: _*),
    //     _.destReg -> op.destReg.U(muonParams.pRegBits.W),
    //     _.reservationAt -> op.reservationAt.U(32.W),
    //     _.operandsDelay -> op.operandsDelay.U(32.W),
    //     _.debugId -> op.debugId.U(lsuDerived.debugIdBits.get.W),
    //   )
    // })
    // val stimulusBundlesVec = VecInit(stimulusBundles)

    // (stimulusModules zip stimulusBundlesVec).foreach { case (module, bundle) =>
    //   module.io.stimulus := bundle
    // }

    val stimulusModules = scalaStimulus(i).map(op => Module(new StimulusModuleV1(op)))

    if (stimulusModules.length == 0) {
      io.coreReservations(i).req.valid := false.B
      io.coreReservations(i).req.bits := DontCare
    } else {
      val stimulusReservationArbiter = Module(new Arbiter(new LsuReservationReq, stimulusModules.length))
      stimulusModules zip stimulusReservationArbiter.io.in foreach { case (sm, arbIn) =>
        arbIn :<>= sm.io.coreReservationReq
      }
      io.coreReservations(i).req :<>= stimulusReservationArbiter.io.out

      stimulusModules foreach { sm =>
        sm.io.coreReservationResp := io.coreReservations(i).resp
      }
    }

    allStimulusModules ++= stimulusModules
  }

  val stimulusReqArbiter = Module(new Arbiter(new LsuRequest, allStimulusModules.length))
  allStimulusModules zip stimulusReqArbiter.io.in foreach { case (sm, arbIn) =>
    arbIn :<>= sm.io.coreReq
  }
  io.coreReq :<>= stimulusReqArbiter.io.out

  val responseChecker = Module(new ResponseChecker()(p))
  responseChecker.io.coreResp :<>= io.coreResp

  val allDone = allStimulusModules.map(_.io.done).reduce(_ && _)
  done := allDone && responseChecker.io.done
}

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
        "INST_BITS"    -> p(MuonKey).instBits,
        "NUM_WARPS"    -> p(MuonKey).numWarps,
        "NUM_LANES"    -> p(MuonKey).numLanes,
        "OP_BITS"      -> Isa.opcodeBits,
        "REG_BITS"     -> Isa.regBits,
        "IMM_BITS"     -> 32,
        "CSR_IMM_BITS" -> Isa.csrImmBits,
        "PRED_BITS"    -> Isa.predBits,
        "SIM_TICK"     -> (if (tick) 1 else 0),
  )) with HasBlackBoxResource with HasCoreParameters {
    val io = IO(new Bundle {
      val clock = Input(Clock())
      val reset = Input(Bool())

      val trace = Input(new Bundle {
        val valid = Bool()
        val pc = pcT
        val warpId = widT
        val tmask = tmaskT
        val regs = Vec(Isa.maxNumRegs, new Bundle {
          val enable = Bool()
          val address = pRegT
          val data = UInt((muonParams.numLanes * muonParams.archLen).W)
        })
      })
    })

    addResource("/vsrc/CyclotronDiffTest.v")
    addResource("/vsrc/Cyclotron.vh")
    addResource("/csrc/Cyclotron.cc")
  }
}

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

// UnitTest harnesses
// ------------------

class MuonCoreTest(timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dutDiplomacy = LazyModule(new MuonCoreTestbench()(p))
  val dut = Module(dutDiplomacy.module)
  io.finished := dut.io.finished
}

class MuonFrontendTest(timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(new MuonFrontendTestbench()(p))
  io.finished := dut.io.finished
}

class MuonBackendTest(timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(new MuonBackendTestbench()(p))
  io.finished := dut.io.finished
}

class MuonLSUTest(timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dutDiplomacy = LazyModule(new MuonLSUTestbench()(p))
  val dut = Module(dutDiplomacy.module)
  io.finished := dut.io.finished
}
