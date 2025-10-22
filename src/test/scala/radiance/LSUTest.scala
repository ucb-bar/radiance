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
        val addr: Seq[Int],
        val storeData: Seq[Int],
    );

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

    it should "accept a store request from core, generate downstream store request on right interface with right data, then ack on core interface" in {
        
        test(new LoadStoreUnit()(params)).withAnnotations(Seq(WriteVcdAnnotation))
        { dut => 

            val (reservationFire, token) = attemptReservation(dut, 0, AddressSpace.globalMemory, MemOp.storeWord)
            assert(reservationFire, "reservation should succeed out of reset")

            dut.clock.step()
            clearReservation(dut, 0)

            dut.clock.step(cycles = 5)

            val request = new CoreRequest(
                token,
                0,
                0,
                Seq.fill(16)(0x1000_0000),
                Seq.tabulate(16)(tid => tid)
            )

            val requestFire = coreRequest(dut, request)
            assert(requestFire, "request should always succeed")
            dut.clock.step()
            clearRequest(dut)

            // wait for global store to be generated
            while (!dut.io.globalMemReq.valid.peekBoolean()) {
                dut.clock.step()
            }

            dut.clock.step(5)
        }
    }
}

