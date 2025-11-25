package radiance.memory
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
