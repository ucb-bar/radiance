package radiance.memory

import freechips.rocketchip.rocket.{NonBlockingDCache, NonBlockingDCacheModule, AMOALU, StoreGen}
import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket

/* The AMOALU in NBDCache is responsible for generating partial store data to be written into data array
   Unfortunately it is hardcoded to be xLen wide; we can't fake this with DummyCoreParams because too many
   things depend on it being 32 or 64, so we have to do this (somewhat hacky) workaround where we make
   a wider AMOALU then replace every output of the old, narrow AMOALU with this wide AMOALU. 
*/

class WideNonBlockingDCache(staticIdForMetadataUseOnly: Int)(implicit p: Parameters) extends NonBlockingDCache(staticIdForMetadataUseOnly) {
    override lazy val module = new WideNonBlockingDCacheModule(this)
}

class WideNonBlockingDCacheModule(outer: NonBlockingDCache) extends NonBlockingDCacheModule(outer) {
    val wideAmoalu = Module(new AMOALU(wordBits))
    when ((s2_valid || s2_replay) && (rocket.isWrite(s2_req.cmd) || s2_data_correctable)) {
        s3_req.data := Mux(s2_data_correctable, s2_data_corrected, wideAmoalu.io.out)
    }

    val wideBypasses = List(
        ((s2_valid_masked || s2_replay) && !s2_sc_fail, s2_req, wideAmoalu.io.out),
        (s3_valid, s3_req, s3_req.data),
        (s4_valid, s4_req, s4_req.data)
    ).map(r => (r._1 && (s1_addr >> wordOffBits === r._2.addr >> wordOffBits) && rocket.isWrite(r._2.cmd), r._3))

    when (s1_clk_en) {
        when (wideBypasses.map(_._1).reduce(_||_)) {
            s2_store_bypass_data := PriorityMux(wideBypasses)
        }
    }

    wideAmoalu.io.mask := new StoreGen(s2_req.size, s2_req.addr, 0.U, wordBits/8).mask
    wideAmoalu.io.cmd := s2_req.cmd
    wideAmoalu.io.lhs := s2_data_word
    wideAmoalu.io.rhs := s2_req.data
}