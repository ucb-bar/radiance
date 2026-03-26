package radiance.unittest

import chisel3._
import chiseltest._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange}
import freechips.rocketchip.subsystem.WithoutTLMonitors
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.scalatest.flatspec.AnyFlatSpec
import radiance.memory.AliasingTLRAM

import scala.collection.mutable.ArrayBuffer

class AliasingTLRAMTB(implicit p: Parameters) extends LazyModule {
  val clientNode = TLClientNode(Seq(
    TLMasterPortParameters.v1(Seq(
      TLMasterParameters.v1(
        name = "aliasing-tlram-test-client",
        sourceId = IdRange(4, 16),
        requestFifo = true
      )
    ))
  ))

  val ram = LazyModule(new AliasingTLRAM(
    sizeInKB = 4,
    widthInBytes = 8,
    targetAddress = AddressSet(0x1000, 0xfff)
  ))

  ram.manager := clientNode

  lazy val module = new AliasingTLRAMTBImp(this)
}

class AliasingTLRAMTBImp(outer: AliasingTLRAMTB) extends LazyModuleImp(outer) {
  val client = outer.clientNode.makeIOs().head
  private val edge = outer.clientNode.out.head._2

  val gen = IO(new Bundle {
    val putFullA = Output(chiselTypeOf(client.a.bits))
    val putPartialA = Output(chiselTypeOf(client.a.bits))
    val getA = Output(chiselTypeOf(client.a.bits))

    val putBackpressure0 = Output(chiselTypeOf(client.a.bits))
    val putBackpressure1 = Output(chiselTypeOf(client.a.bits))
    val putBackpressure2 = Output(chiselTypeOf(client.a.bits))

    val prefill0 = Output(chiselTypeOf(client.a.bits))
    val prefill1 = Output(chiselTypeOf(client.a.bits))
    val prefill2 = Output(chiselTypeOf(client.a.bits))
    val getBackpressure0 = Output(chiselTypeOf(client.a.bits))
    val getBackpressure1 = Output(chiselTypeOf(client.a.bits))
    val getBackpressure2 = Output(chiselTypeOf(client.a.bits))
  })

  gen.putFullA := edge.Put(
    fromSource = 4.U,
    toAddress = 0x1010.U,
    lgSize = 3.U,
    data = "h1122334455667788".U
  )._2

  gen.putPartialA := edge.Put(
    fromSource = 5.U,
    toAddress = 0x1010.U,
    lgSize = 3.U,
    data = "h00000000aabbccdd".U,
    mask = "h0f".U
  )._2

  gen.getA := edge.Get(
    fromSource = 6.U,
    toAddress = 0x1010.U,
    lgSize = 3.U
  )._2

  gen.putBackpressure0 := edge.Put(7.U, 0x1020.U, 3.U, "h0101010101010101".U)._2
  gen.putBackpressure1 := edge.Put(8.U, 0x1028.U, 3.U, "h0202020202020202".U)._2
  gen.putBackpressure2 := edge.Put(9.U, 0x1030.U, 3.U, "h0303030303030303".U)._2

  gen.prefill0 := edge.Put(10.U, 0x1040.U, 3.U, "h1111111111111111".U)._2
  gen.prefill1 := edge.Put(11.U, 0x1048.U, 3.U, "h2222222222222222".U)._2
  gen.prefill2 := edge.Put(12.U, 0x1050.U, 3.U, "h3333333333333333".U)._2
  gen.getBackpressure0 := edge.Get(13.U, 0x1040.U, 3.U)._2
  gen.getBackpressure1 := edge.Get(14.U, 0x1048.U, 3.U)._2
  gen.getBackpressure2 := edge.Get(15.U, 0x1050.U, 3.U)._2
}

class AliasingTLRAMTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "AliasingTLRAM"

  implicit val p: Parameters = Parameters.empty

  private def init(c: AliasingTLRAMTBImp): Unit = {
    c.client.a.valid.poke(false.B)
    c.client.d.ready.poke(true.B)
  }

  private def driveA(c: AliasingTLRAMTBImp, bits: TLBundleA): Unit = {
    c.client.a.bits.opcode.poke(bits.opcode.peek())
    c.client.a.bits.param.poke(bits.param.peek())
    c.client.a.bits.size.poke(bits.size.peek())
    c.client.a.bits.source.poke(bits.source.peek())
    c.client.a.bits.address.poke(bits.address.peek())
    c.client.a.bits.mask.poke(bits.mask.peek())
    c.client.a.bits.data.poke(bits.data.peek())
    c.client.a.bits.corrupt.poke(bits.corrupt.peek())
    c.client.a.bits.user.poke(bits.user.peek())
    c.client.a.bits.echo.poke(bits.echo.peek())
  }

  private def sendReq(c: AliasingTLRAMTBImp, bits: TLBundleA, maxCycles: Int = 20): Unit = {
    driveA(c, bits)
    c.client.a.valid.poke(true.B)
    var cycles = 0
    while (!c.client.a.ready.peek().litToBoolean && cycles < maxCycles) {
      c.clock.step()
      cycles += 1
    }
    require(c.client.a.ready.peek().litToBoolean, "A request was not accepted in time")
    c.clock.step()
    c.client.a.valid.poke(false.B)
  }

  private def recvAck(
      c: AliasingTLRAMTBImp,
      expSource: Int,
      expSize: Int,
      expData: Option[BigInt],
      maxCycles: Int = 30
  ): Unit = {
    var cycles = 0
    while (!c.client.d.valid.peek().litToBoolean && cycles < maxCycles) {
      c.clock.step()
      cycles += 1
    }
    require(c.client.d.valid.peek().litToBoolean, s"D response source=$expSource did not arrive in time")

    expData match {
      case Some(d) =>
        c.client.d.bits.opcode.expect(TLMessages.AccessAckData)
        c.client.d.bits.data.expect(d.U)
      case None =>
        c.client.d.bits.opcode.expect(TLMessages.AccessAck)
    }
    c.client.d.bits.source.expect(expSource.U)
    c.client.d.bits.size.expect(expSize.U)
    c.clock.step()
  }

  it should "support PutFull, PutPartial and Get with read-after-write data correctness" in {
    test(LazyModule(new AliasingTLRAMTB()(new WithoutTLMonitors())).module) { c =>
      init(c)

      sendReq(c, c.gen.putFullA)
      recvAck(c, expSource = 4, expSize = 3, expData = None)

      sendReq(c, c.gen.putPartialA)
      recvAck(c, expSource = 5, expSize = 3, expData = None)

      sendReq(c, c.gen.getA)
      recvAck(c, expSource = 6, expSize = 3, expData = Some(BigInt("11223344aabbccdd", 16)))
    }
  }

  it should "apply backpressure correctly for write acknowledgments and recover without dropping requests" in {
    test(LazyModule(new AliasingTLRAMTB()(new WithoutTLMonitors())).module) { c =>
      init(c)
      c.client.d.ready.poke(false.B)

      sendReq(c, c.gen.putBackpressure0)
      sendReq(c, c.gen.putBackpressure1)

      driveA(c, c.gen.putBackpressure2)
      c.client.a.valid.poke(true.B)
      var sawStall = false
      for (_ <- 0 until 8) {
        sawStall ||= !c.client.a.ready.peek().litToBoolean
        c.clock.step()
      }
      assert(sawStall, "Expected A-channel stall while D-channel backpressure is asserted")

      c.client.d.ready.poke(true.B)
      var req2Accepted = false
      val seenSources = ArrayBuffer[Int]()
      var cycles = 0
      while ((seenSources.length < 3 || !req2Accepted) && cycles < 30) {
        val aFire = c.client.a.valid.peek().litToBoolean && c.client.a.ready.peek().litToBoolean
        val dFire = c.client.d.valid.peek().litToBoolean && c.client.d.ready.peek().litToBoolean
        if (dFire) {
          c.client.d.bits.opcode.expect(TLMessages.AccessAck)
          c.client.d.bits.size.expect(3.U)
          seenSources += c.client.d.bits.source.peek().litValue.toInt
        }
        c.clock.step()
        if (aFire) {
          req2Accepted = true
          c.client.a.valid.poke(false.B)
        }
        cycles += 1
      }

      assert(req2Accepted, "Third write request was not accepted after releasing backpressure")
      assert(seenSources == Seq(7, 8, 9), s"Write response order/source mismatch: ${seenSources.mkString(",")}")
    }
  }

  it should "apply backpressure correctly for read responses and preserve read data integrity" in {
    test(LazyModule(new AliasingTLRAMTB()(new WithoutTLMonitors())).module) { c =>
      init(c)

      sendReq(c, c.gen.prefill0)
      recvAck(c, expSource = 10, expSize = 3, expData = None)
      sendReq(c, c.gen.prefill1)
      recvAck(c, expSource = 11, expSize = 3, expData = None)
      sendReq(c, c.gen.prefill2)
      recvAck(c, expSource = 12, expSize = 3, expData = None)

      c.client.d.ready.poke(false.B)
      sendReq(c, c.gen.getBackpressure0)
      sendReq(c, c.gen.getBackpressure1)

      driveA(c, c.gen.getBackpressure2)
      c.client.a.valid.poke(true.B)
      var sawStall = false
      for (_ <- 0 until 8) {
        sawStall ||= !c.client.a.ready.peek().litToBoolean
        c.clock.step()
      }
      assert(sawStall, "Expected A-channel stall for reads while D-channel backpressure is asserted")

      c.client.d.ready.poke(true.B)
      var req2Accepted = false
      val seen = ArrayBuffer[(Int, BigInt)]()
      var cycles = 0
      while ((seen.length < 3 || !req2Accepted) && cycles < 40) {
        val aFire = c.client.a.valid.peek().litToBoolean && c.client.a.ready.peek().litToBoolean
        val dFire = c.client.d.valid.peek().litToBoolean && c.client.d.ready.peek().litToBoolean
        if (dFire) {
          c.client.d.bits.opcode.expect(TLMessages.AccessAckData)
          c.client.d.bits.size.expect(3.U)
          seen += ((c.client.d.bits.source.peek().litValue.toInt, c.client.d.bits.data.peek().litValue))
        }
        c.clock.step()
        if (aFire) {
          req2Accepted = true
          c.client.a.valid.poke(false.B)
        }
        cycles += 1
      }

      assert(req2Accepted, "Third read request was not accepted after releasing backpressure")
      val seenStr = seen.map { case (s, d) => s"($s,0x${d.toString(16)})" }.mkString(",")
      assert(
        seen == Seq(
          (13, BigInt("1111111111111111", 16)),
          (14, BigInt("2222222222222222", 16)),
          (15, BigInt("3333333333333333", 16))
        ),
        s"Read response source/data mismatch: $seenStr"
      )
    }
  }
}
