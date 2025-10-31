// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.unittest

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import freechips.rocketchip.tile.TileKey
import freechips.rocketchip.unittest.{UnitTest, UnitTestModule}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import radiance.cluster.FakeRadianceClusterTileParams
import radiance.muon._
import freechips.rocketchip.tilelink.{
  TLMasterPortParameters, TLClientParameters, TLClientNode, TLMasterParameters,
  TLRAM, TLXbar, TLBuffer
}
import freechips.rocketchip.diplomacy.{
  IdRange, AddressSet
}
import radiance.muon.backend.LaneRecomposer

/** Testbench for Muon with the test signals */
class MuonTestbench(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val finished = Bool()
  })

  val muon = Module(new MuonCore()(p))
  muon.io.imem.resp.valid := false.B
  muon.io.imem.resp.bits := DontCare
  muon.io.imem.req.ready := false.B
  
  muon.io.dmem.resp.foreach(_.valid := false.B)
  muon.io.dmem.resp.foreach(_.bits := DontCare)
  muon.io.dmem.req.foreach(_.ready := false.B)
  muon.io.smem.resp.foreach(_.valid := false.B)
  muon.io.smem.resp.foreach(_.bits := DontCare)
  muon.io.smem.req.foreach(_.ready := false.B)

  io.finished := true.B
}

/** Testbench for Muon frontend pipe */
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
  fe.io.csr.read := 0.U.asTypeOf(fe.io.csr.read)
  fe.io.hartId := 0.U

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
  cbe.io.imem.req <> fe.io.imem.req
  fe.io.imem.resp <> cbe.io.imem.resp

  io.finished := cbe.io.finished

  dontTouch(fe.io)
}

/** Testbench for Muon backend pipe */
class MuonBackendTestbench(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val finished = Bool()
  })

  val be = Module(new Backend()(p.alterMap(Map(
    TileKey -> FakeRadianceClusterTileParams(None, p(MuonKey), 0),
  ))))
  be.io.dmem.resp.foreach(_.valid := false.B)
  be.io.dmem.resp.foreach(_.bits := DontCare)
  be.io.dmem.req.foreach(_.ready := false.B)
  be.io.smem.resp.foreach(_.valid := false.B)
  be.io.smem.resp.foreach(_.bits := DontCare)
  be.io.smem.req.foreach(_.ready := false.B)

  val cfe = Module(new CyclotronFrontend()(p))
  // Imem in the ISA model is not used
  cfe.io.imem.req.valid := false.B
  cfe.io.imem.req.bits := DontCare
  cfe.io.imem.resp.ready := false.B

  // TODO: also instantiate CyclotronBackend as the issue downstream

  (be.io.ibuf zip cfe.io.ibuf).foreach { case (b, f) =>
    b.valid := f.valid
    f.ready := b.ready
    b.bits := f.bits.toUop()
  }
  dontTouch(be.io)

  // TODO: connect finished from the backend
  io.finished := cfe.io.finished
}

/** Testbench for Muon backend LSU pipe */
class LSUWrapper(implicit p: Parameters) extends LazyModule with HasMuonCoreParameters {
  val sourceIdsPerLane = 1 << lsuDerived.sourceIdBits
  
  // every lsu lane is a separate TL client
  def masterParams = (lane: Int) => {
    val sourceId = IdRange(
      lane * sourceIdsPerLane,
      (lane + 1) * sourceIdsPerLane
    )

    TLMasterParameters.v1(
      name = f"lsu-lane$lane",
      sourceId = sourceId,
      requestFifo = false
    )
  }

  val node = TLClientNode(Seq.tabulate(muonParams.lsu.numLsuLanes) { lane =>
    TLMasterPortParameters.v1(
      Seq(masterParams(lane))
    )
  })

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

    // connect lsu to wrapper interface
    (io.coreReservations zip lsu.io.coreReservations).foreach {
      case (i, l) => {
        l.req :<>= i.req
        i.resp :<>= l.resp
      }
    }

    lsu.io.coreReq :<>= io.coreReq
    io.coreResp :<>= lsu.io.coreResp
    
    // connect lsu to core memory interface
    lsuAdapter.io.lsu.globalMemReq :<>= lsu.io.globalMemReq
    lsu.io.globalMemResp :<>= lsuAdapter.io.lsu.globalMemResp

    lsuAdapter.io.lsu.shmemReq :<>= lsu.io.shmemReq
    lsu.io.shmemResp :<>= lsuAdapter.io.lsu.shmemResp

    // connect gmem to tl client node
    MuonMemTL.multiConnectTL(
      lsuAdapter.io.core.dmem.req,
      lsuAdapter.io.core.dmem.resp,
      node
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
      for (j <- 0 until 100) {
        // random memory operation: only loads / stores for now
        val memOpNum = scala.util.Random.nextInt(8)
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

        val address = Seq.fill(muonParams.numLanes) {
          if (scala.util.Random.nextDouble() < 0.3 && memoryState.nonEmpty) {
            // 30% chance to repeat an address we've looked at before
            val keys = memoryState.keys.toSeq
            val randomOffset = memOpNum match {
              case 0 | 1 | 5 => scala.util.Random.nextInt(4)
              case 2 | 3 | 6 => scala.util.Random.nextInt(2) * 2
              case 4 | 7     => 0
            }
            keys(scala.util.Random.nextInt(keys.length)) + randomOffset
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
              memoryState.getOrElse(word, 0L) >> (offset * 8) match {
                case b if (b & 0x80) != 0 => (b - 256)
                case b => b & 0xffL
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
              memoryState.getOrElse(word, 0L) >> (offset * 8) & 0xffL
            })
            val bools = tmask
            Some((destReg, bools, data))
          }
          case 2 => {
            // LH
            val data = address.map(a => {
              val word = a & ~0x3
              val offset = (a & 0x2).toInt
              memoryState.getOrElse(word, 0L) >> (offset * 8) match {
                case h if (h & 0x8000) != 0 => (h - 65536)
                case h => h & 0xffffL
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
              memoryState.getOrElse(word, 0L) >> (offset * 8) & 0xffffL
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
                val oldValue = memoryState.getOrElse(word, 0L)
                
                val newByte = oldValue & ~(0xffL << (offset * 8)) | 
                  (data(lane).toLong & 0xffL) << (offset * 8)
                memoryState(word) = newByte
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
                val oldValue = memoryState.getOrElse(word, 0L)
                
                val newHalf = oldValue & ~(0xffffL << (offset * 8)) | 
                  (data(lane).toLong & 0xffffL) << (offset * 8)
                memoryState(word) = newHalf
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
          operandsDelay = scala.util.Random.nextInt(10).toLong,
          expectedWriteback = expectedWriteback
        )

        debugId += 1
        reservation += scala.util.Random.nextInt(5).toLong
      }
      memOpsPerWarp += memOps.toSeq
    }
    
    memOpsPerWarp
  }

  val scalaStimulus = stimulus(0)

  // convert it down into 1 module per memory operation
  // this is "synthesizable" in some sense :)
  class StimulusModule(memoryOp: MemoryOperation) extends CoreModule {
    val io = IO(new Bundle {
      val coreReservationReq = Decoupled(new LsuReservationReq)
      val coreReservationResp = Flipped(Valid(new LsuReservationResp))
      
      val coreReq = Decoupled(new LsuRequest)
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
        io.coreReservationReq.bits.debugId.get := 0.U
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
        io.coreReq.bits.token := token

        when (io.coreReq.fire) {
          state := s_done
        }
      }

      is(s_done) {/* nop */}
    }
  }

  class ResponseChecker(implicit p: Parameters) extends CoreModule {
    val io = IO(new Bundle {
      val coreResp = Flipped(Decoupled(new LsuResponse))
    })

    class ExpectedWritebackBundle extends Bundle {
      val warpId = UInt(muonParams.warpIdBits.W)
      val destReg = UInt(muonParams.pRegBits.W)
      val tmask = Vec(muonParams.numLanes, Bool())
      val data = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
    }
    val expectedWritebackT = new ExpectedWritebackBundle
    val allMemoryOps = scalaStimulus.flatten.sortInPlaceBy(_.debugId).map(
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
    // collect packets together
    val recomposer = Module(new LaneRecomposer(
      inLanes = 1,
      outLanes = muonParams.numLanes / muonParams.lsu.numLsuLanes,
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
      assert(tmask === expected.tmask, cf"Mismatched tmask in LSU response ($tmask) vs expected (${expected.tmask})")

      val dataConcat = typedPackets.flatMap(_.writebackData)
      for (i <- 0 until muonParams.numLanes) {
        assert(dataConcat(i) === expected.data(i), cf"Mismatched writeback data lane $i in LSU response (${dataConcat(i)}) vs expected ${expected.data(i)})")
      }
    }
  }

  val allStimulusModules = scala.collection.mutable.ArrayBuffer[StimulusModule]()
  for (i <- 0 until muonParams.numWarps) {
    val stimulusModules = scalaStimulus(i).map(op => Module(new StimulusModule(op)))
    
    val stimulusReservationArbiter = Module(new Arbiter(new LsuReservationReq, stimulusModules.length))
    stimulusModules zip stimulusReservationArbiter.io.in foreach { case (sm, arbIn) =>
      arbIn :<>= sm.io.coreReservationReq
    }
    io.coreReservations(i).req :<>= stimulusReservationArbiter.io.out

    stimulusModules foreach { sm =>
      sm.io.coreReservationResp := io.coreReservations(i).resp
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
}

class MuonLSUTestbench(implicit p: Parameters) extends LazyModule {
  val lsuWrapper = LazyModule(new LSUWrapper()(p))
  val xbar = TLXbar()
  val fakeGmem = TLRAM(
    address = AddressSet(0x0, 0xfffff),
    beatBytes = p(MuonKey).archLen / 8
  )
  
  // we need a buffer in between because lsu expects atomic (all-lanes-at-once) requests to
  // downstream memory interface - checks all ready before setting valid; arbitration in xbar 
  // leads to combinational loop if there is no buffer between
  xbar :=* TLBuffer() :=* lsuWrapper.node
  fakeGmem := xbar
  
  lazy val module = new MuonLSUTestbenchImp
  class MuonLSUTestbenchImp extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val finished = Bool()
    })

    val stimulus = Module(new SynthesizableStimulus()(p))

    // connect stimulus to lsu wrapper
    lsuWrapper.module.io <> stimulus.io

    io.finished := false.B
  }
}

/** Wraps Verilog shim to convert messy flattened 1-D arrays into
 *  Chisel-friendly IOs. */
class CyclotronFrontend(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val imem = Flipped(new InstMemIO)
    val ibuf = Vec(muonParams.numWarps, Decoupled(new InstBufferEntry))
    val finished = Output(Bool())
  })

  val cfbox = Module(new CyclotronFrontendBlackBox()(p))
  cfbox.io.clock := clock
  cfbox.io.reset := reset.asBool

  // helpers for connecting flattened Verilog IO to Chisel
  def splitUInt(flattened: UInt, wordBits: Int): Vec[UInt] = {
    require(flattened.getWidth % wordBits == 0)
    val numWords = flattened.getWidth / wordBits
    VecInit.tabulate(numWords)(i => flattened((i + 1) * wordBits - 1, i * wordBits))
  }
  def connectSplit(destWord: UInt, flattened: UInt, index: Int) = {
    require(destWord.widthKnown)
    destWord := splitUInt(flattened, destWord.getWidth)(index)
  }

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
    connectSplit(ib.bits.pc, cfbox.io.ibuf.pc, i)
    connectSplit(ib.bits.wid, cfbox.io.ibuf.wid, i)
    connectSplit(ib.bits.op, cfbox.io.ibuf.op, i)
    connectSplit(ib.bits.rd, cfbox.io.ibuf.rd, i)
    connectSplit(ib.bits.rs1, cfbox.io.ibuf.rs1, i)
    connectSplit(ib.bits.rs2, cfbox.io.ibuf.rs2, i)
    connectSplit(ib.bits.rs3, cfbox.io.ibuf.rs3, i)
    connectSplit(ib.bits.imm32, cfbox.io.ibuf.imm32, i)
    connectSplit(ib.bits.imm24, cfbox.io.ibuf.imm24, i)
    connectSplit(ib.bits.csrImm, cfbox.io.ibuf.csrImm, i)
    connectSplit(ib.bits.f3, cfbox.io.ibuf.f3, i)
    connectSplit(ib.bits.f7, cfbox.io.ibuf.f7, i)
    connectSplit(ib.bits.pred, cfbox.io.ibuf.pred, i)
    connectSplit(ib.bits.tmask, cfbox.io.ibuf.tmask, i)
    connectSplit(ib.bits.raw, cfbox.io.ibuf.raw, i)
  }

  io.finished := cfbox.io.finished
}

class CyclotronFrontendBlackBox(implicit val p: Parameters) extends BlackBox(Map(
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
    with HasBlackBoxResource with HasMuonCoreParameters {

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val imem = Flipped(new InstMemIO)
    // flattened for all numWarps
    val ibuf = new Bundle with HasInstBufferEntryFields {
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
  addResource("/csrc/Cyclotron.cc")
}

class CyclotronBackendBlackBox(implicit val p: Parameters) extends BlackBox(Map(
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
    with HasBlackBoxResource with HasMuonCoreParameters with HasCoreBundles {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val imem = Flipped(new InstMemIO)
    val issue = Flipped(Vec(muonParams.numWarps, Decoupled(new InstBufferEntry)))
    val commit = schedWritebackT

    val finished = Output(Bool())
  })

  addResource("/vsrc/CyclotronBackend.v")
  addResource("/csrc/Cyclotron.cc")
}

class CyclotronMemBlackBox(implicit val p: Parameters) extends BlackBox(Map(
      "ARCH_LEN"  -> p(MuonKey).archLen,
      "NUM_WARPS" -> p(MuonKey).numWarps,
      "NUM_LANES" -> p(MuonKey).numLanes,
      "OP_BITS"   -> Isa.opcodeBits,
      "REG_BITS"  -> Isa.regBits,
      "IMM_BITS"  -> 32,
      "PRED_BITS" -> Isa.predBits,
      "TAG_BITS"  -> 32,
      "LSU_LANES" -> p(MuonKey).lsu.numLsuLanes,
    ))
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
  addResource("/csrc/Cyclotron.cc")
}

// UnitTest harnesses
// ------------------

class MuonTest(timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(new MuonTestbench()(p))
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
