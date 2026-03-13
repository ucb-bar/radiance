package radiance.subsystem

import chisel3._
import freechips.rocketchip.diplomacy.{AddressSet, BufferParams}
import freechips.rocketchip.subsystem.TLBusWrapperLocation
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.{Config, Field}
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import radiance.memory.AliasingTLRAM
import testchipip.soc.{SubsystemInjector, SubsystemInjectorKey}

case class ContingentSpadParams(
  widthInBytes: Int,
  sizeInKB: Int,
  where: TLBusWrapperLocation
)

case object ContingentSpadKey extends Field[Option[ContingentSpadParams]](None)

case object ContingentSpadInjector extends SubsystemInjector((p, baseSubsystem) => {
  p(ContingentSpadKey).map { spadParams =>
    val bus = baseSubsystem.locateTLBusWrapper(spadParams.where)
    val domain = bus.generateSynchronousDomain

    domain {
      implicit val q = p
      val GPUMemParams(gmemAddr, gmemSize) = p(GPUMemory).get
      val spad = LazyModule(new AliasingTLRAM(
        spadParams.sizeInKB,
        spadParams.widthInBytes,
        AddressSet(gmemAddr + gmemSize, gmemSize - 1) // 0x1_8000_0000 to 0x2_0000_0000 by default
      ))
      bus.coupleTo(s"contingency-spad") { busOut =>
        (spad.manager
          := TLBuffer(BufferParams(2))
          := TLWidthWidget(bus.beatBytes)
          := TLFragmenter(spadParams.widthInBytes, bus.blockBytes, alwaysMin = true)
          := TLSourceShrinker(8)
          := busOut)
      }
    }
  }
})

class WithContingentSpad(widthInBytes: Int = 8, sizeInKB: Int = 16, where: TLBusWrapperLocation)
  extends Config((site, here, up) => {
  case ContingentSpadKey => Some(ContingentSpadParams(widthInBytes, sizeInKB, where))
  case SubsystemInjectorKey => up(SubsystemInjectorKey) + ContingentSpadInjector
})
