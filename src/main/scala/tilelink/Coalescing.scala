// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.unittest._


class CoalescingLogic(threads : Int = 1)(implicit p: Parameters)
    extends LazyModule {

        val node = TLIdentityNode()

        // Creating N number of Manager node
        val beatBytes = 8
        val seqparam = Seq(TLSlaveParameters.v1(
                          address = Seq(AddressSet(0x0000, 0xffffff)),
                          //resources = device.reg,
                          regionType = RegionType.UNCACHED,
                          executable = true,
                          supportsArithmetic = TransferSizes(1, beatBytes),
                          supportsLogical = TransferSizes(1, beatBytes),
                          supportsGet = TransferSizes(1, beatBytes),
                          supportsPutFull = TransferSizes(1, beatBytes),
                          supportsPutPartial = TransferSizes(1, beatBytes),
                          supportsHint = TransferSizes(1, beatBytes),
                          fifoId = Some(0))
                          )
        val vec_node_entry = Seq.tabulate(threads){
              _ => TLManagerNode(Seq(TLSlavePortParameters.v1(seqparam, beatBytes)))
        }
        //Assign each vec_node to the identity node
        vec_node_entry.foreach { n => n := node }



        lazy val module = new Impl
        class Impl extends LazyModuleImp(this) {

            //Example 1: accessing the entire A channel data for Thread 0
            val (tl_in_0, edge0) = vec_node_entry(0).in(0)
            dontTouch(tl_in_0.a)

            // Example 2: accssing the entire A channel data for Thread 1
            val (tl_in_1, edge1) = vec_node_entry(1).in(0)
            dontTouch(tl_in_1.a)
        }
    }



class CoalescingEntry(txns: Int = 5000)(implicit p: Parameters)
    extends LazyModule {


  val node = TLIdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {

    (node.in zip node.out) foreach { 
      case((in, edgeIn), (out, edgeOut)) =>
          out.a <> in.a
          in.d  <> out.d
          dontTouch(in.a)
          dontTouch(in.d)
    }

  }

}

class MemTraceDriver(threads : Int = 1)(implicit p: Parameters) extends LazyModule {
  

  // Create N client nodes together
  val vec_trace_node = Seq.tabulate(threads) { i =>
    
    val clients = Seq(TLMasterParameters.v1(
                      name = "MemTraceDriver" + i.toString,
                      sourceId = IdRange(0,4)
                        )
                      )
    TLClientNode(Seq(TLMasterPortParameters.v1(clients)))
  }
  

  // Combine N outgoing client node into 1 idenity node for diplomatic connection
  val node = TLIdentityNode()
  vec_trace_node.foreach{ thread_node => 
      node := thread_node
  }


  lazy val module = new MemTraceDriverImp(this, "YourTraceFileName", threads)

}


class MemTraceDriverImp(outer: MemTraceDriver, trace_file: String, threads : Int) (implicit p: Parameters) extends LazyModuleImp(outer) with UnitTestModule {


    // Creating N indepdent behaving thread modules
    val vec_sim = Seq.tabulate(threads) { i =>
      val ith_file_name = trace_file + (i+1).toString
      Module(new SimMemTrace(trace_file=ith_file_name, 4))
    }

    // Connect each sim module to its respective TL connection
    vec_sim.zipWithIndex.foreach{
      case (sim, i) =>
        sim.io.clock := clock
        sim.io.reset := reset.asBool
        sim.io.trace_read.ready := true.B

        val (tl_out, edgesOut) = outer.vec_trace_node(i).out(0)
        tl_out.a.valid := sim.io.trace_read.valid
        tl_out.a.bits := edgesOut.Put(
                          fromSource = 0.U,
                          toAddress = 0.U,
                          // 64 bits = 8 bytes = 2**(3) bytes
                          lgSize = 3.U,
                          data = (i+100).U)._2
        //tl_out.a.bits.mask := 0xf.U
        dontTouch(tl_out.a)

        tl_out.d.ready := true.B  

    }
    

  

    // we're finished when there is no more memtrace to read
    io.finished := sim.io.trace_read.finished
  }



class SimMemTrace(val trace_file: String, num_threads: Int) extends BlackBox(Map("TRACE_FILE" -> trace_file)) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val trace_read = new Bundle {
      val ready = Input(Bool())
      val valid = Output(UInt(num_threads.W))
      // Chisel can't interface with Verilog 2D port, so flatten all lanes into
      // single wide 1D array.
      val address = Output(UInt((64 * num_threads).W))
      val finished = Output(Bool())
    }
  })

  addResource("/vsrc/SimMemTrace.v")
  addResource("/csrc/SimMemTrace.cc")
  addResource("/csrc/SimMemTrace.h")
}


class CoalConnectTrace(txns: Int)(implicit p: Parameters) extends LazyModule {

    val coal_entry = LazyModule(new CoalescingEntry(txns))
    val coal_logic = LazyModule(new CoalescingLogic(threads=2))
    val driver = LazyModule(new MemTraceDriver(threads=2))

    coal_logic.node :=* coal_entry.node :=* driver.node

    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) with UnitTestModule {
    driver.module.io.start := io.start
    io.finished := driver.module.io.finished
  }

}

class CoalescingUnitTest(txns: Int = 5000, timeout: Int = 500000)(implicit
    p: Parameters
) extends UnitTest(timeout) {
  // val coal = Module(LazyModule(new CoalescingUnit(txns)).module)
  val driver = Module(LazyModule(new MemTraceDriver).module)
  driver.io.start := io.start

  val dut = Module(LazyModule(new CoalConnectTrace(txns)).module)
  dut.io.start := io.start
  io.finished := dut.io.finished

}
