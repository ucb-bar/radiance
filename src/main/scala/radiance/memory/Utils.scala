package radiance.memory

import chisel3._
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.tilelink._
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.cde.config.Parameters

object guardMonitors {
  def apply[T](callback: Parameters => T)(implicit p: Parameters, disableMonitors: Boolean): Unit = {
    if (disableMonitors) {
      DisableMonitors { callback }
    } else {
      callback(p)
    }
  }
}

object connectOne {
  def apply[T <: TLNode](from: TLNode, to: () => T)
                        (implicit p: Parameters, disableMonitors: Boolean): T = {
    val t = to()
    guardMonitors { implicit p => t := from }
    t
  }
}

object connectXbarName {
  def apply(from: TLNode, name: Option[String] = None,
            policy: TLArbiter.Policy = TLArbiter.roundRobin)
            (implicit p: Parameters, disableMonitors: Boolean): TLNexusNode = {
    val t = LazyModule(new TLXbar(policy))
    name.map(t.suggestName)
    guardMonitors { implicit p => t.node := from }
    t.node
  }
}

object connectXbar {
  def apply(from: TLNode)(implicit p: Parameters, disableMonitors: Boolean): TLNexusNode = {
    connectXbarName(from, None)
  }
}

object connectEphemeral {
  def apply(from: TLNode)(implicit p: Parameters): TLEphemeralNode = {
    connectOne(from, TLEphemeralNode.apply)(p, true)
  }
}

object connectIdentity {
  def apply(from: TLNode)(implicit p: Parameters): TLIdentityNode = {
    connectOne(from, TLIdentityNode.apply)(p, true)
  }
}

object idleMaster {
  def apply(sourceBits: Int = 2, name: String = "idle_master"): TLClientNode = {
    TLClientNode(Seq(TLMasterPortParameters.v2(
      masters = Seq(TLMasterParameters.v2(
        name = name,
        sourceId = IdRange(0, 1 << sourceBits)
      ))
    )))
  }
}

object BoolArrayUtils {
  implicit class BoolSeqUtils(v: Seq[Bool]) {
    def orR: Bool = VecInit(v).orR
    def andR: Bool = VecInit(v).andR
  }

  implicit class BoolVecUtils(v: Vec[Bool]) {
    def orR: Bool = if (v.isEmpty) false.B else v.asUInt.orR
    def andR: Bool = if (v.isEmpty) true.B else v.asUInt.andR
  }
}
