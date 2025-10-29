package radiance.core

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import radiance.muon.LoadStoreUnit
import radiance.subsystem._
import radiance.muon.AddressSpaceCfg
import radiance.muon.MemOp
import radiance.muon.AddressSpace
import radiance.muon.LsuQueueToken

class LSUTest extends AnyFlatSpec with ChiselScalatestTester {
    behavior of "LSU"

    val params = new WithMuonCores(1) ++
        new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16)

    val narrowLsuParams = new WithMuonCores(1) ++
        new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 4)

    // Helper to check if token matches expected values
    def checkToken(token: LsuQueueToken, warpId: Int, addressSpace: AddressSpace.Type, ldq: Boolean, index: Int = 0): Unit = {
        assert(token.warpId.litValue == warpId, s"WarpId mismatch: expected $warpId, got ${token.warpId.litValue}")
        // Compare addressSpace as literal value
        assert(token.addressSpace.litValue == addressSpace.litValue, s"AddressSpace mismatch")
        assert(token.ldq.litToBoolean == ldq, s"LDQ flag mismatch: expected $ldq, got ${token.ldq.litToBoolean}")
        assert(token.index.litValue == index, s"Index mismatch: expected $index, got ${token.index.litValue}")
    }

    it should "compile" in {
        test(new LoadStoreUnit()(params))
        { c => () }
    }

    def attemptReservation(dut: LoadStoreUnit, warp: Int, addressSpace: AddressSpace.Type, op: MemOp.Type): (Boolean, LsuQueueToken) = {
        val reservation = dut.io.coreReservations(warp)
        reservation.req.bits.addressSpace.poke(addressSpace)
        reservation.req.bits.op.poke(op)
        reservation.req.valid.poke(true)

        val fire = reservation.req.ready.peekBoolean()
        
        assert(fire == reservation.resp.valid.peekBoolean(), 
            "reservation response should be combinationally coupled to reservation request")
        
        val token = reservation.resp.bits.token.peek()
        (fire, token)
    }

    def clearReservation(dut: LoadStoreUnit, warp: Int) = {
        val reservation = dut.io.coreReservations(warp)
        reservation.req.valid.poke(false)
    }

    class CoreRequest(
        val token: LsuQueueToken,
        val rd: Int,
        val offset: Int,
        val addr: Seq[Long],
        val storeData: Seq[Long],
    );

  // Initialize external interfaces to avoid X propagation in waveforms
  def initExternalIfaces(dut: LoadStoreUnit): Unit = {
      // Downstream memory accepts
      dut.io.globalMemReq.ready.poke(true)
      dut.io.shmemReq.ready.poke(true)

      // Upstream memory responses deasserted and cleared
      dut.io.globalMemResp.valid.poke(false)
      dut.io.globalMemResp.bits.tag.poke(0)
      for (v <- dut.io.globalMemResp.bits.valid) v.poke(false)
      for (d <- dut.io.globalMemResp.bits.data) d.poke(0)

      dut.io.shmemResp.valid.poke(false)
      dut.io.shmemResp.bits.tag.poke(0)
      for (v <- dut.io.shmemResp.bits.valid) v.poke(false)
      for (d <- dut.io.shmemResp.bits.data) d.poke(0)

      // Core writeback sink ready
      dut.io.coreResp.ready.poke(true)

      // Core request interface idle
      dut.io.coreReq.valid.poke(false)
      dut.io.coreReq.bits.destReg.poke(0)
      dut.io.coreReq.bits.imm.poke(0)
      for (a <- dut.io.coreReq.bits.address) a.poke(0)
      for (d <- dut.io.coreReq.bits.storeData) d.poke(0)
      for (t <- dut.io.coreReq.bits.tmask) t.poke(false)

      // Initialize coreReservations (inputs to LSU) to benign defaults
      for (w <- 0 until dut.muonParams.numWarps) {
          val r = dut.io.coreReservations(w)
          r.req.valid.poke(false)
          r.req.bits.addressSpace.poke(AddressSpace.globalMemory)
          r.req.bits.op.poke(MemOp.loadWord)
      }
  }

    def coreRequest(dut: LoadStoreUnit, request: CoreRequest): Boolean = {
        val coreReq = dut.io.coreReq
        coreReq.bits.token.poke(request.token)
        coreReq.bits.destReg.poke(request.rd)
        coreReq.bits.imm.poke(request.offset)
        for ((a, b) <- coreReq.bits.address zip request.addr) {
            a.poke(b)
        }

        for ((a, b) <- coreReq.bits.storeData zip request.storeData) {
            a.poke(b)
        }

        for (t <- coreReq.bits.tmask) {
            t.poke(true)
        }

        coreReq.valid.poke(true)

        val fire = coreReq.ready.peekBoolean()
        fire
    }

    def clearRequest(dut: LoadStoreUnit) = {
        dut.io.coreReq.valid.poke(false)
    }

    // Helper to check memory request fields
    def checkMemRequest(dut: LoadStoreUnit, addresses: Seq[Int], data: Seq[Int], op: MemOp.Type): Unit = {
        assert(dut.io.globalMemReq.valid.peekBoolean() || dut.io.shmemReq.valid.peekBoolean(), 
            "Memory request should be valid")
        
        // Get the appropriate request interface
        val req = if (dut.io.globalMemReq.valid.peekBoolean()) dut.io.globalMemReq else dut.io.shmemReq
        
        // Compare op as raw value - simplified check for now
        val opLit = req.bits.op.peek().litValue
        val expectedLit = op.litValue
        assert(opLit == expectedLit, s"Op mismatch: expected $op")
        
        for ((addr, i) <- addresses.zipWithIndex) {
            assert(req.bits.address(i).peekInt() == addr, s"Address mismatch at lane $i: expected $addr")
        }
        
        for ((d, i) <- data.zipWithIndex) {
            assert(req.bits.data(i).peekInt() == d, s"Data mismatch at lane $i: expected $d")
        }
    }

    // Helper to provide memory response
    def provideMemResponse(dut: LoadStoreUnit, tag: Int, valid: Seq[Boolean], data: Seq[Int]): Unit = {
        // Determine which interface to use based on context
        val resp = dut.io.globalMemResp // Default to global for now
        
        resp.valid.poke(true)
        resp.bits.tag.poke(tag)
        
        for ((v, i) <- valid.zipWithIndex) {
            resp.bits.valid(i).poke(v)
        }
        for ((d, i) <- data.zipWithIndex) {
            resp.bits.data(i).poke(d)
        }
        
        dut.clock.step()
        resp.valid.poke(false)
    }

    // Helper to check writeback response
    def checkWriteback(dut: LoadStoreUnit, expectedWarp: Int, expectedData: Seq[Int]): Unit = {
        var waitedCycles = 0
        while (!dut.io.coreResp.valid.peekBoolean() && waitedCycles < 100) {
            dut.clock.step()
            waitedCycles += 1
        }
        
        assert(dut.io.coreResp.valid.peekBoolean(), "Writeback should be valid")
        assert(dut.io.coreResp.bits.warpId.peekInt() == expectedWarp, s"WarpId mismatch: expected $expectedWarp")
        
        for ((d, i) <- expectedData.zipWithIndex) {
            assert(dut.io.coreResp.bits.writebackData(i).peekInt() == d, s"Writeback data mismatch at lane $i")
        }
    }

    // === Basic Load/Store Tests ===
    
    it should "reserve and execute a load from global memory" in {
        test(new LoadStoreUnit()(params)).withAnnotations(Seq(VcsBackendAnnotation, WriteFsdbAnnotation)) { dut =>
            initExternalIfaces(dut)
            // Reserve load
            val (fire, token) = attemptReservation(dut, 0, AddressSpace.globalMemory, MemOp.loadWord)
            assert(fire, "Load reservation should succeed")
            checkToken(token, 0, AddressSpace.globalMemory, ldq = true, index = 0)

            dut.clock.step()
            clearReservation(dut, 0)

            // Provide core request
            val addr: Seq[Long] = Seq.fill(16)(0x1000_0000L)
            val request = new CoreRequest(token, rd = 5, offset = 0, addr = addr, storeData = Seq.fill(16)(0))
            
            val reqFire = coreRequest(dut, request)
            assert(reqFire, "Load request should be accepted")
            dut.clock.step()
            clearRequest(dut)

            // Wait for memory request
            var waited = 0
            while (!dut.io.globalMemReq.valid.peekBoolean() && waited < 100) {
                dut.clock.step()
                waited += 1
            }
            
            assert(dut.io.globalMemReq.valid.peekBoolean(), "Global memory request should be generated")
        }
    }
    
    
    it should "reserve and execute a store to shared memory" in {
        test(new LoadStoreUnit()(params)).withAnnotations(Seq(VcsBackendAnnotation, WriteVpdAnnotation)) { dut =>
            initExternalIfaces(dut)
            // Reserve store to shared memory
            val (fire, token) = attemptReservation(dut, 0, AddressSpace.sharedMemory, MemOp.storeWord)
            assert(fire, "Shared store reservation should succeed")
            checkToken(token, 0, AddressSpace.sharedMemory, ldq = false, index = 0)

            dut.clock.step()
            clearReservation(dut, 0)

            // Provide core request
            val addr: Seq[Long] = Seq.fill(16)(0x2000_0000L)
            val storeData: Seq[Long] = Seq.tabulate(16)(i => 0xDEAD_BEEFL + i)
            val request = new CoreRequest(token, rd = 0, offset = 0, addr = addr, storeData = storeData)
            
            val reqFire = coreRequest(dut, request)
            assert(reqFire, "Store request should be accepted")
            dut.clock.step()
            clearRequest(dut)

            // Wait for shared memory request
            var waited = 0
            while (!dut.io.shmemReq.valid.peekBoolean() && waited < 100) {
                dut.clock.step()
                waited += 1
            }
            
            assert(dut.io.shmemReq.valid.peekBoolean(), "Shared memory request should be generated")
        }
    }

    it should "support different load sizes (byte, half, word)" in {
        test(new LoadStoreUnit()(params)) { dut =>
            initExternalIfaces(dut)
            for ((op, opName) <- Seq(
                (MemOp.loadByte, "loadByte"),
                (MemOp.loadHalf, "loadHalf"),
                (MemOp.loadWord, "loadWord")
            )) {
                val (fire, _) = attemptReservation(dut, 0, AddressSpace.globalMemory, op)
                assert(fire, s"$opName reservation should succeed")
                dut.clock.step()
                clearReservation(dut, 0)
                dut.clock.step(10)
            }
        }
    }

    it should "support different store sizes (byte, half, word)" in {
        test(new LoadStoreUnit()(params)) { dut =>
            initExternalIfaces(dut)
            for ((op, opName) <- Seq(
                (MemOp.storeByte, "storeByte"),
                (MemOp.storeHalf, "storeHalf"),
                (MemOp.storeWord, "storeWord")
            )) {
                val (fire, _) = attemptReservation(dut, 0, AddressSpace.globalMemory, op)
                assert(fire, s"$opName reservation should succeed")
                dut.clock.step()
                clearReservation(dut, 0)
                dut.clock.step(10)
            }
        }
    }

    // === Queue Capacity Tests ===

    it should "reject reservations when global LDQ is full" in {
        test(new LoadStoreUnit()(params)) { dut =>
            initExternalIfaces(dut)
            // Fill up the global LDQ (8 entries)
            for (i <- 0 until dut.muonParams.lsu.numGlobalLdqEntries) {
                val (fire, _) = attemptReservation(dut, 0, AddressSpace.globalMemory, MemOp.loadWord)
                assert(fire, s"Reservation $i should succeed")
                dut.clock.step()
                clearReservation(dut, 0)
                dut.clock.step()
            }

            // 9th reservation should fail
            val (fire, _) = attemptReservation(dut, 0, AddressSpace.globalMemory, MemOp.loadWord)
            assert(!fire, "9th load reservation should be rejected")
        }
    }

    it should "reject reservations when global STQ is full" in {
        test(new LoadStoreUnit()(params)) { dut =>
            initExternalIfaces(dut)
            // Fill up the global STQ (4 entries)
            for (i <- 0 until dut.muonParams.lsu.numGlobalStqEntries) {
                val (fire, _) = attemptReservation(dut, 0, AddressSpace.globalMemory, MemOp.storeWord)
                assert(fire, s"Reservation $i should succeed")
                dut.clock.step()
                clearReservation(dut, 0)
                dut.clock.step()
            }

            // 5th reservation should fail
            val (fire, _) = attemptReservation(dut, 0, AddressSpace.globalMemory, MemOp.storeWord)
            assert(!fire, "5th store reservation should be rejected")
        }
    }

    // === Multi-Warp Tests ===

    it should "handle reservations from multiple warps independently" in {
        test(new LoadStoreUnit()(params)) { dut =>
            initExternalIfaces(dut)
            // Each warp should have independent queues
            for (warp <- 0 until dut.muonParams.numWarps) {
                val (fire, token) = attemptReservation(dut, warp, AddressSpace.globalMemory, MemOp.loadWord)
                assert(fire, s"Reservation for warp $warp should succeed")
                checkToken(token, warp, AddressSpace.globalMemory, ldq = true)
                dut.clock.step()
                clearReservation(dut, warp)
                dut.clock.step()
            }
        }
    }

    // === Load-Store Hazard Tests ===

    it should "enforce ordering: load blocked by store" in {
        test(new LoadStoreUnit()(params)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
            dut.reset.asBool.poke(true.B)
            dut.clock.step(5)
            dut.reset.asBool.poke(false.B)

            // Ensure all external interfaces are driven to known values
            initExternalIfaces(dut)
            
            // Reserve a store
            val (storeFire, storeToken) = attemptReservation(dut, 0, AddressSpace.globalMemory, MemOp.storeWord)
            assert(storeFire, "Store reservation should succeed")
            
            dut.clock.step()
            clearReservation(dut, 0)
            dut.clock.step()

            // Reserve a load
            val (loadFire, loadToken) = attemptReservation(dut, 0, AddressSpace.globalMemory, MemOp.loadWord)
            assert(loadFire, "Load reservation should succeed")
            
            dut.clock.step()
            clearReservation(dut, 0)
            dut.clock.step()

            // Provide operands for load
            val loadAddr: Seq[Long] = Seq.fill(16)(0x1000_0000L)
            val loadRequest = new CoreRequest(loadToken, rd = 1, offset = 0, addr = loadAddr, storeData = Seq.fill(16)(0))
            coreRequest(dut, loadRequest)
            dut.clock.step()
            clearRequest(dut)

            // Not fully comprehensive safety test, but good enough
            for (_ <- 0 until 50) {
                val valid = dut.io.globalMemReq.valid.peekBoolean()
                assert(!valid, "load should be blocked by store")
                dut.clock.step()
            }
        }
    }

    // === Out-of-Order Operand Tests ===

    it should "handle operand arrival after reservation" in {
        test(new LoadStoreUnit()(params)) { dut =>
            initExternalIfaces(dut)
            // Reserve a load
            val (fire, token) = attemptReservation(dut, 0, AddressSpace.globalMemory, MemOp.loadWord)
            assert(fire, "Load reservation should succeed")
            
            dut.clock.step()
            clearReservation(dut, 0)

            // Wait some cycles
            dut.clock.step(10)

            // Then provide operands
            val addr: Seq[Long] = Seq.fill(16)(0x1000_0000L)
            val request = new CoreRequest(token, rd = 5, offset = 0, addr = addr, storeData = Seq.fill(16)(0))
            val reqFire = coreRequest(dut, request)
            assert(reqFire, "Load request should be accepted after delay")
            dut.clock.step()
            clearRequest(dut)
        }
    }

    // === Empty Queue Tests ===

    it should "report empty when all queues are empty" in {
        test(new LoadStoreUnit()(params)) { dut =>
            initExternalIfaces(dut)
            assert(dut.io.empty.peekBoolean(), "LSU should be empty after reset")
        }
    }

    it should "not report empty when queues have entries" in {
        test(new LoadStoreUnit()(params)) { dut =>
            initExternalIfaces(dut)
            // Add a reservation
            val (fire, _) = attemptReservation(dut, 0, AddressSpace.globalMemory, MemOp.loadWord)
            assert(fire, "Reservation should succeed")
            
            dut.clock.step()
            clearReservation(dut, 0)
            
            // Should not be empty immediately
            assert(!dut.io.empty.peekBoolean(), "LSU should not be empty with queued entries")
        }
    }
}

