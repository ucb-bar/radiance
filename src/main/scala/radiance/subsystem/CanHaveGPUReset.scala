package radiance.subsystem

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.resources.{BigIntHexContext, SimpleDevice}
import freechips.rocketchip.subsystem.{CBUS, Cluster, InstantiatesHierarchicalElements, RocketSubsystem}
import freechips.rocketchip.tilelink.{TLFragmenter, TLRegisterNode, TLSourceShrinker}
import org.chipsalliance.cde.config.{Config, Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import radiance.cluster.{RadianceCluster, SoftResetFinishNode}
import radiance.muon.MuonTile
import testchipip.soc.{SubsystemInjector, SubsystemInjectorKey}

case class GPUResetParams(
  address: BigInt = 0x4000,
  defaultReset: Boolean = true // doesn't boot until rocket tells it to
)

case object GPUResetKey extends Field[Option[GPUResetParams]](None)

case object GPUResetInjector extends SubsystemInjector((p, baseSubsystem) => {
  p(GPUResetKey).map { resetParams =>
    val cbus = baseSubsystem.locateTLBusWrapper(CBUS)
    val gpuResetClockDomain = cbus.generateSynchronousDomain

    gpuResetClockDomain {
      implicit val q = p
      baseSubsystem match {
        case subsystem: InstantiatesHierarchicalElements =>
          val clusters = subsystem.cluster_prci_domains.map(_._2.element.asInstanceOf[Cluster])
          val radClusters = clusters.filter(_.isInstanceOf[RadianceCluster])
            .asInstanceOf[Iterable[RadianceCluster]]
          val muonTiles = radClusters.flatMap(_.muonTiles.filter(_.isInstanceOf[MuonTile]))
            .asInstanceOf[Iterable[MuonTile]]
          val softResetFinishSlaves = muonTiles.map(_.softResetFinishSlave)

          val resetAggregator = LazyModule(new GPUResetAggregator(resetParams, softResetFinishSlaves.toSeq))
          cbus.coupleTo(s"gpu-reset") { cbusOut =>
            (resetAggregator.regNode
              := TLFragmenter(8, 32)
              := TLSourceShrinker(8)
              := cbusOut)
          }
      }
    }
  }
})

class WithGPUResetAggregator(address: BigInt = x"4100_0000", defaultReset: Boolean = true)
  extends Config((site, here, up) => {
  case GPUResetKey => Some(GPUResetParams(
    address, defaultReset
  ))
  case SubsystemInjectorKey => up(SubsystemInjectorKey) + GPUResetInjector
})

class GPUResetAggregator(params: GPUResetParams, slaves: Seq[SoftResetFinishNode.Slave])
                        (implicit p: Parameters) extends LazyModule {

  val softResetFinishMasters = slaves.map { s =>
    val master = SoftResetFinishNode.Master()
    s := master
    master
  }

  val regDevice = new SimpleDevice(f"radiance-reset-aggregator", Seq("radiance-reset-aggregator"))
  val regNode = TLRegisterNode(
    address = Seq(AddressSet(params.address, 0xff)),
    device = regDevice,
    beatBytes = 8,
    concurrency = 1
  )

  lazy val module = new LazyModuleImp(this) {
    val bundles = softResetFinishMasters.map(_.out.head._1)
    val coreResets = bundles.map { b =>
      val latchedReset = RegInit(params.defaultReset.B)
      b.softReset := latchedReset
      latchedReset
    }

    def softReset(valid: Bool, bits: UInt): Bool = {
      when (valid) {
        coreResets.foreach(_ := (bits =/= 0.U))
      }
      true.B
    }

    if (slaves.nonEmpty) {
      val coreFinishes = VecInit(bundles.map(_.finished))
      val allFinished = coreFinishes.reduceTree(_ && _)
      regNode.regmap(
        Seq(
          0x00 -> Seq(RegField.w(32, softReset(_, _))),
          0x08 -> Seq(RegField.r(32, allFinished))
        ) ++
        coreFinishes.zipWithIndex.map { case (finish, gcid) =>
          0x10 + gcid * 0x04 -> Seq(RegField.r(32, finish))
        }
      : _*)

      val (_, stopSim) = Counter(0 until 1024, allFinished, !allFinished)
      when (stopSim) {
        stop("no more active warps for 1k cycles\n")
      }
    }

  }
}


