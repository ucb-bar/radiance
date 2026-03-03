package radiance.memory

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes}
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.subsystem.WithoutTLMonitors
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.scalatest.flatspec.AnyFlatSpec

class FlitMergeNodeTB(implicit p: Parameters) extends LazyModule {
  private val device = new SimpleDevice("flit-merge-node-test", Seq("radiance,flit-merge-node-test"))

  val clientNode = TLClientNode(Seq(
    TLMasterPortParameters.v1(Seq(
      TLMasterParameters.v1(
        name = "flit-merge-client",
        sourceId = IdRange(0, 8),
        requestFifo = true
      )
    ))
  ))

  val merge = LazyModule(new FlitMergeNode(from = 4, to = 8))

  val managerNode = TLManagerNode(Seq(
    TLSlavePortParameters.v1(
      Seq(TLSlaveParameters.v2(
        address = Seq(AddressSet(0x0, 0xfff)),
        resources = device.reg,
        regionType = RegionType.UNCACHED,
        executable = false,
        supports = TLMasterToSlaveTransferSizes(
          get = TransferSizes(1, 8),
          putFull = TransferSizes(1, 8),
          putPartial = TransferSizes(1, 8)
        ),
        fifoId = Some(0)
      )),
      beatBytes = 8
    )
  ))

  managerNode := merge.node := clientNode

  lazy val module = new FlitMergeNodeTBImp(this)
}

class FlitMergeNodeTBImp(outer: FlitMergeNodeTB) extends LazyModuleImp(outer) {
  val client = outer.clientNode.makeIOs().head
  val manager = outer.managerNode.makeIOs().head

  private val clientEdge = outer.clientNode.out.head._2
  private val managerEdge = outer.managerNode.in.head._2

  val gen = IO(new Bundle {
    val req4LoS1 = Output(chiselTypeOf(client.a.bits))
    val req4HiS2 = Output(chiselTypeOf(client.a.bits))
    val req8S3 = Output(chiselTypeOf(client.a.bits))
    val ackS2Sz3 = Output(chiselTypeOf(manager.d.bits))
    val ackS3Sz3 = Output(chiselTypeOf(manager.d.bits))
  })

  // 4B requests that should merge into one 8B request.
  gen.req4LoS1 := clientEdge.Put(
    fromSource = 1.U,
    toAddress = 0x0.U,
    lgSize = 2.U,
    data = "h0000000011223344".U,
    mask = "h0f".U
  )._2
  gen.req4HiS2 := clientEdge.Put(
    fromSource = 2.U,
    toAddress = 0x4.U,
    lgSize = 2.U,
    data = "h5566778800000000".U,
    mask = "hf0".U
  )._2

  // 8B passthrough request.
  gen.req8S3 := clientEdge.Put(
    fromSource = 3.U,
    toAddress = "h20".U,
    lgSize = 3.U,
    data = "hdeadbeefcafebabe".U
  )._2

  // 8B manager responses.
  gen.ackS2Sz3 := managerEdge.AccessAck(toSource = 2.U, lgSize = 3.U)
  gen.ackS3Sz3 := managerEdge.AccessAck(toSource = 3.U, lgSize = 3.U)
}

class FlitMergeNodeTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "FlitMergeNode"

  implicit val p: Parameters = Parameters.empty

  private def init(c: FlitMergeNodeTBImp): Unit = {
    c.client.a.valid.poke(false.B)
    c.client.d.ready.poke(true.B)
    c.manager.a.ready.poke(true.B)
    c.manager.d.valid.poke(false.B)
  }

  private def driveClientA(c: FlitMergeNodeTBImp, bits: TLBundleA): Unit = {
    c.client.a.bits.opcode.poke(bits.opcode.peek())
    c.client.a.bits.param.poke(bits.param.peek())
    c.client.a.bits.size.poke(bits.size.peek())
    c.client.a.bits.source.poke(bits.source.peek())
    c.client.a.bits.address.poke(bits.address.peek())
    c.client.a.bits.user.poke(bits.user.peek())
    c.client.a.bits.echo.poke(bits.echo.peek())
    c.client.a.bits.mask.poke(bits.mask.peek())
    c.client.a.bits.data.poke(bits.data.peek())
    c.client.a.bits.corrupt.poke(bits.corrupt.peek())
    c.client.a.valid.poke(true.B)
  }

  private def driveManagerD(c: FlitMergeNodeTBImp, bits: TLBundleD): Unit = {
    c.manager.d.bits.opcode.poke(bits.opcode.peek())
    c.manager.d.bits.param.poke(bits.param.peek())
    c.manager.d.bits.size.poke(bits.size.peek())
    c.manager.d.bits.source.poke(bits.source.peek())
    c.manager.d.bits.sink.poke(bits.sink.peek())
    c.manager.d.bits.denied.poke(bits.denied.peek())
    c.manager.d.bits.user.poke(bits.user.peek())
    c.manager.d.bits.echo.poke(bits.echo.peek())
    c.manager.d.bits.data.poke(bits.data.peek())
    c.manager.d.bits.corrupt.poke(bits.corrupt.peek())
    c.manager.d.valid.poke(true.B)
  }

  it should "respond to both 4B halves with matching source/size and merge into one 8B request" in {
    test(LazyModule(new FlitMergeNodeTB()(new WithoutTLMonitors())).module) { c =>
      init(c)

      driveClientA(c, c.gen.req4LoS1)
      c.client.a.ready.expect(true.B)
      c.client.d.valid.expect(true.B)
      c.client.d.bits.opcode.expect(TLMessages.AccessAck)
      c.client.d.bits.source.expect(1.U)
      c.client.d.bits.size.expect(2.U)
      c.manager.a.valid.expect(false.B)
      c.clock.step()

      driveClientA(c, c.gen.req4HiS2)
      c.client.a.ready.expect(true.B)
      c.client.d.valid.expect(false.B)
      c.manager.a.valid.expect(true.B)
      c.manager.a.bits.opcode.expect(TLMessages.PutPartialData)
      c.manager.a.bits.source.expect(2.U)
      c.manager.a.bits.size.expect(3.U)
      c.manager.a.bits.address.expect(0.U)
      c.manager.a.bits.mask.expect("hff".U)
      c.manager.a.bits.data.expect("h5566778811223344".U)
      c.clock.step()

      c.client.a.valid.poke(false.B)
      driveManagerD(c, c.gen.ackS2Sz3)
      c.client.d.valid.expect(true.B)
      c.client.d.bits.opcode.expect(TLMessages.AccessAck)
      c.client.d.bits.source.expect(2.U)
      c.client.d.bits.size.expect(2.U)
      c.clock.step()
    }
  }

  it should "pass 8B through when mixed between 4B halves and preserve mixed response ordering" in {
    test(LazyModule(new FlitMergeNodeTB()(new WithoutTLMonitors())).module) { c =>
      init(c)

      // First mergeable 4B request -> immediate synthetic response on client D.
      driveClientA(c, c.gen.req4LoS1)
      c.client.a.ready.expect(true.B)
      c.client.d.valid.expect(true.B)
      c.client.d.bits.source.expect(1.U)
      c.manager.a.valid.expect(false.B)
      c.clock.step()

      // 8B request mixed between 4B halves should pass through untouched.
      driveClientA(c, c.gen.req8S3)
      c.client.a.ready.expect(true.B)
      c.client.d.valid.expect(false.B)
      c.manager.a.valid.expect(true.B)
      c.manager.a.bits.opcode.expect(TLMessages.PutFullData)
      c.manager.a.bits.source.expect(3.U)
      c.manager.a.bits.size.expect(3.U)
      c.manager.a.bits.address.expect("h20".U)
      c.manager.a.bits.mask.expect("hff".U)
      c.manager.a.bits.data.expect("hdeadbeefcafebabe".U)
      c.clock.step()

      // Second mergeable 4B request completes merge and emits merged 8B request.
      driveClientA(c, c.gen.req4HiS2)
      c.client.a.ready.expect(true.B)
      c.client.d.valid.expect(false.B)
      c.manager.a.valid.expect(true.B)
      c.manager.a.bits.opcode.expect(TLMessages.PutPartialData)
      c.manager.a.bits.source.expect(2.U)
      c.manager.a.bits.size.expect(3.U)
      c.manager.a.bits.address.expect(0.U)
      c.manager.a.bits.mask.expect("hff".U)
      c.manager.a.bits.data.expect("h5566778811223344".U)
      c.clock.step()

      // Response for 8B passthrough arrives between the two 4B responses.
      c.client.a.valid.poke(false.B)
      driveManagerD(c, c.gen.ackS3Sz3)
      c.client.d.valid.expect(true.B)
      c.client.d.bits.source.expect(3.U)
      c.client.d.bits.size.expect(3.U)
      c.clock.step()

      // Response for merged 8B request arrives afterward, translated back to 4B size.
      driveManagerD(c, c.gen.ackS2Sz3)
      c.client.d.valid.expect(true.B)
      c.client.d.bits.source.expect(2.U)
      c.client.d.bits.size.expect(2.U)
      c.clock.step()
    }
  }

  it should "stall first 4B request for one cycle when 8B response arrives and then acknowledge it next cycle" in {
    test(LazyModule(new FlitMergeNodeTB()(new WithoutTLMonitors())).module) { c =>
      init(c)

      // Same cycle: manager has a passthrough response, client drives first 4B request.
      driveManagerD(c, c.gen.ackS3Sz3)
      driveClientA(c, c.gen.req4LoS1)
      c.client.a.ready.expect(false.B) // stalled due to out.d.valid priority
      c.manager.a.valid.expect(false.B)
      c.client.d.valid.expect(true.B)
      c.client.d.bits.source.expect(3.U)
      c.client.d.bits.size.expect(3.U)
      c.clock.step()

      // Next cycle, old manager response is gone: first 4B request must be accepted and acked.
      c.manager.d.valid.poke(false.B)
      driveClientA(c, c.gen.req4LoS1)
      c.client.a.ready.expect(true.B)
      c.client.d.valid.expect(true.B)
      c.client.d.bits.source.expect(1.U)
      c.manager.a.valid.expect(false.B)
      c.clock.step()

      // Complete merge to ensure stalled first 4B request was not dropped.
      driveClientA(c, c.gen.req4HiS2)
      c.client.a.ready.expect(true.B)
      c.manager.a.valid.expect(true.B)
      c.manager.a.bits.opcode.expect(TLMessages.PutPartialData)
      c.manager.a.bits.source.expect(2.U)
      c.manager.a.bits.size.expect(3.U)
      c.manager.a.bits.address.expect(0.U)
      c.manager.a.bits.mask.expect("hff".U)
      c.manager.a.bits.data.expect("h5566778811223344".U)
      c.clock.step()

      c.client.a.valid.poke(false.B)
      driveManagerD(c, c.gen.ackS2Sz3)
      c.client.d.valid.expect(true.B)
      c.client.d.bits.source.expect(2.U)
      c.client.d.bits.size.expect(2.U)
      c.clock.step()
    }
  }
}
