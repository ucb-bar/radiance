package freechips.rocketchip.tilelink

import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.subsystem.BaseSubsystem
import org.chipsalliance.cde.config.Parameters

// The trait is attached to DigitalTop of Chipyard system, informing it indeed
// has the ability to attach GPU tracer node onto the system bus
trait CanHaveMemtraceCore { this: BaseSubsystem =>
  implicit val p: Parameters

  p(MemtraceCoreKey).map { param =>
    // Safe to use get as WithMemtraceCore requires WithNLanes to be defined
    val simtParam = p(SIMTCoreKey).get
    val config = defaultConfig.copy(numLanes = simtParam.nLanes)
    val tracer = LazyModule(
      new MemTraceDriver(config, param.tracefilename, param.traceHasSource)(p)
    )
    // Must use :=* to ensure the N edges from Tracer doesn't get merged into 1
    // when connecting to SBus
    println(
      s"============ MemTraceDriver instantiated [filename=${param.tracefilename}]"
    )
    val upstream = p(CoalescerKey) match {
      case Some(coalParam) => {
        val coal = LazyModule(new CoalescingUnit(coalParam))
        println(s"============ CoalescingUnit instantiated [numLanes=${coalParam.numLanes}]")
        coal.cpuNode :=* tracer.node // N lanes
        coal.aggregateNode           // N+1 lanes
      }
      case None => tracer.node
    }
    sbus.fromPort(Some("gpu-tracer"))() :=* upstream
  }
}
