
package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.{BaseSubsystem, CacheBlockBytes}
import freechips.rocketchip.config.{Parameters, Field, Config}

// class class, consumed by WithGPUTacer config and GPUTracerKey

case class GPUTracerConfig(numLanes: Int, traceFile : String) // FIXME, add lane number and file name

case object GPUTracerKey extends Field[Option[GPUTracerConfig]](None)



// Both LazyModule of Tracer and Impl are both in Coalescing.scala


//The trait is attached to DigitalTop of Chipyard system, informing it indeed has the ability 
//to attach GPU tracer node  onto the system bus
trait CanHaveGPUTracer { this: BaseSubsystem =>
  implicit val p: Parameters

  //p(GPUTracerKey) is the mechnimism to pass Config's parameter down to lazymodule
  p(GPUTracerKey) .map { k =>
    val config = p(GPUTracerKey).get
    val tracer = LazyModule(new MemTraceDriver(defaultConfig, config.traceFile)(p))
    // Must use :=* to ensure the N edges from Tracer doesn't get merged into 1 when connecting to SBus
    sbus.fromPort(Some("gpu-tracer"))() :=* tracer.node
  }
}


//This is used by Chip Level Config, the config which creates the SoC
class WithGPUTracer(numLanes: Int, traceFile : String) extends Config((site, here, up) => {
    case GPUTracerKey => Some( GPUTracerConfig(numLanes, traceFile) )
}
)




