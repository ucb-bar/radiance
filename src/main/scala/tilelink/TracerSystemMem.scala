package freechips.rocketchip.tilelink

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.{BaseSubsystem}
import org.chipsalliance.cde.config.{Parameters, Config}

// The trait is attached to DigitalTop of Chipyard system, informing it indeed
// has the ability to attach GPU tracer node onto the system bus
trait CanHaveGPUTracer { this: BaseSubsystem =>
  implicit val p: Parameters

  p(SIMTCoreKey).map { _ =>
    val config = p(SIMTCoreKey).get
    val tracer = LazyModule(new MemTraceDriver(defaultConfig, config.tracefilename)(p))
    // Must use :=* to ensure the N edges from Tracer doesn't get merged into 1
    // when connecting to SBus
    sbus.fromPort(Some("gpu-tracer"))() :=* tracer.node
  }
}

//This is used by Chip Level Config, the config which creates the SoC
class WithGPUTracer(numLanes: Int, tracefilename: String)
    extends Config((_, _, _) => { case SIMTCoreKey =>
      Some(SIMTCoreParams(numLanes, tracefilename))
    })
