package freechips.rocketchip.tilelink

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.{BaseSubsystem}
import org.chipsalliance.cde.config.{Parameters, Config}

// The trait is attached to DigitalTop of Chipyard system, informing it indeed
// has the ability to attach GPU tracer node onto the system bus
trait CanHaveMemtraceCore { this: BaseSubsystem =>
  implicit val p: Parameters

  p(MemtraceCoreKey).map { param =>
    // Safe to use get as WithMemtraceCore requires WithNLanes to be defined
    val simtParam = p(SIMTCoreKey).get
    val config = defaultConfig.copy(numLanes = simtParam.nLanes)
    val tracer = LazyModule(new MemTraceDriver(config, param.tracefilename)(p))
    // Must use :=* to ensure the N edges from Tracer doesn't get merged into 1
    // when connecting to SBus
    println(s"============ MemTraceDriver instantiated [filename=${param.tracefilename}]")
    sbus.fromPort(Some("gpu-tracer"))() :=* tracer.node
  }
}

//This is used by Chip Level Config, the config which creates the SoC
class WithMemtraceCore(tracefilename: String)
    extends Config((site, _, _) => { case MemtraceCoreKey =>
      require(
        site(SIMTCoreKey).isDefined,
        "Memtrace core requires a SIMT configuration. Use WithNLanes to enable SIMT."
      )
      Some(MemtraceCoreParams(tracefilename))
    })
