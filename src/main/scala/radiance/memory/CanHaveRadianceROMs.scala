package radiance.memory

import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.Parameters

// TODO: possibly move to somewhere closer to CoalescingUnit
// TODO: separate coalescer config from CanHaveMemtraceCore

// The trait is attached to DigitalTop of Chipyard system, informing it indeed
// has the ability to attach GPU tracer node onto the system bus
trait CanHaveRadianceROMs { this: BaseSubsystem with HasTiles =>
  implicit val p: Parameters

  p(RadianceROMsLocated()).foreach(_.foreach { rom => RadianceROM.attachROM(rom, this, CBUS) })

}
