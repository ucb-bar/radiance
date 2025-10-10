package radiance.memory

import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.tilelink.{TLNexusNode, TLSlaveToMasterTransferSizes}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/* this limits all slaves to conform to the region of a given scope */
class AddressScopeNode(addr: AddressSet)(implicit p: Parameters) extends LazyModule {
  val node = TLNexusNode(
    clientFn  = c => {
      require(c.size == 1, s"there should only be one client port, found ${c.size}")
      c.head.v1copy(
        clients = c.head.clients.map(cc => cc.v2copy(
          supports = cc.supports.mincover(TLSlaveToMasterTransferSizes(
            probe = cc.supports.get // very sussy
          ))
        ))
      )
    },
    managerFn = m => {
      println(m)
      require(m.size == 1, s"there should only be one manager port, found ${m.size}")
      m.head.v1copy(
        managers = Seq(m.head.managers.last.v2copy(
          address = Seq(addr),
          supports = m.map(_.anySupportClaims).reduce(_ mincover _)
        )),
      )
    }
  )
  lazy val module = new LazyModuleImp(this) {
    assert(node.in.length == 1, node.in)
    assert(node.out.length == 1, node.out)
    val in = node.in.head._1
    val out = node.out.head._1


    // cannot just do <> because of address bit width mismatch
    {
      out.a.valid := in.a.valid
      in.a.ready := out.a.ready
      val a = out.a.bits
      val b = in.a.bits
      (Seq(a.user, a.opcode, a.size, a.mask, a.param, a.data, a.source) zip
        Seq(b.user, b.opcode, b.size, b.mask, b.param, b.data, b.source))
        .foreach { case (x, y) =>
        x := y
      }
      a.address := b.address.asTypeOf(a.address.cloneType)
    }

//    out.a <> in.a
    in.b <> out.b
    out.c <> in.c
    in.d <> out.d
    out.e <> in.e
  }
}

object AddressScopeNode {
  def apply(addr: AddressSet)(implicit p: Parameters): TLNexusNode = {
    LazyModule(new AddressScopeNode(addr)).node
  }
}
