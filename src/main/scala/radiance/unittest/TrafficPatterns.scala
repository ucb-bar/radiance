package radiance.unittest

import scala.util.Random

abstract class TrafficPattern {
  val name: String = "pattern"
  val lanes: Int = 16
  val lgSize: Int = 2   // request (logical) width
  val busSize: Int = 4  // bus (physical) width

  def reqSize: Int = (1 << lgSize)

  def offset(time: Int, index: Int): Int

  def apply(baseAddr: Int, time: Int, index: Int): ScalaTLA = {
    require(reqSize <= busSize)
    val addr = baseAddr + offset(time, index)
    new ScalaTLA(
      address = (addr / busSize) * busSize,
      lgSize = lgSize,
      data = None,
      mask = Some(((1 << reqSize) - 1) << (addr % busSize))
    )
  }

  def getSmem(clusterId: Int): (Int, Int) => ScalaTLA = {
    this(0x4000_0000 + 0x10_0000 * clusterId, _, _)
  }

  def putSmem(clusterId: Int): (Int, Int) => ScalaTLA = {
    case (x, y) =>
      val getReq = getSmem(clusterId)(x, y)
      getReq.copy(data = Some(getReq.address))
  }
}

object TrafficPatterns {

  class Strided(warpStride: Int = 1, laneStride: Int = 1,
                override val lgSize: Int = 2) extends TrafficPattern {
    override val name = s"strided($warpStride, $laneStride)@$reqSize"
    def offset(t: Int, i: Int) =
      ((t * warpStride) * lanes + i) * laneStride * reqSize
  }

  class Tiled(val tileM: Int = 16, val tileN: Int = 16,
              override val lgSize: Int = 2) extends TrafficPattern {
    override val name = s"tiled($tileM, $tileN)@$reqSize"
    val tileElems = tileM * tileN
    def elemIdx(t: Int, i: Int): Int = t * lanes + i
    def tileIdx(t: Int, i: Int): Int = elemIdx(t, i) / tileElems
    def idxInTile(t: Int, i: Int): Int = elemIdx(t, i) % tileElems
    def inTileCoords(t: Int, i: Int): (Int, Int) =
      (idxInTile(t, i) / tileN, idxInTile(t, i) % tileN)

    def offset(t: Int, i: Int) = {
      val (row, col) = inTileCoords(t, i)
      (tileIdx(t, i) * tileElems + row * tileN + col) * reqSize
    }
  }

  class Swizzled(val tileSize: Int = 16,
                 override val lgSize: Int = 2)extends Tiled(tileSize, tileSize) {
    override val name = s"swizzled($tileSize)@$reqSize"
    override def offset(t: Int, i: Int) = {
      val (row, col) = inTileCoords(t, i)
      val rotatedCol = (col - row % tileSize + tileSize) % tileSize
      (tileIdx(t, i) * tileElems + row * tileSize + rotatedCol) * reqSize
    }
  }

  object Transposed {
    def apply(tiled: Tiled): TrafficPattern = {
      new Tiled {
        override val name = tiled.name + ".T"
        override def inTileCoords(t: Int, i: Int) = {
          val (a, b) = tiled.inTileCoords(t, i)
          (b, a)
        }
      }
    }
  }

  class RandomAccess(min: Int, max: Int, seed: Int = 0,
                     override val lgSize: Int = 2) extends TrafficPattern {
    override val name = s"random($seed)"
    val rng = new Random(seed)
    def offset(t: Int, i: Int) =
      rng.between(min, max) * reqSize
  }

  object Delayed {
    def apply(pattern: TrafficPattern, by: Int): TrafficPattern = {
      new TrafficPattern {
        override val name = pattern.name + s"+$by"
        def offset(t: Int, i: Int) =
          pattern.offset(t + by, i)
      }
    }
  }

  object Bounded {
    def apply(pattern: TrafficPattern, within: Int): TrafficPattern = {
      new TrafficPattern {
        override val name = pattern.name
        final def offset(t: Int, i: Int) = // Bounded must come last
          pattern.offset(t, i) % within
      }
    }
  }


  val strideGrid = for { x <- Seq(1, 2); y <- Seq(1, 2, 8, 0) } yield (x, y)
  val tileGrid = Seq(8, 16, 32, 64, 128)
  val dataTypes = Seq(1, 2) // 4B, 2B

  val stridedPatterns = dataTypes.flatMap { lgSize =>
    strideGrid.map(x => new Strided(x._1, x._2, lgSize))
  }

  val tiledPatterns = dataTypes.flatMap { lgSize =>
    tileGrid.map(x => new Tiled(x, x, lgSize)) // TODO: rectangular tiles
  }

  val swizzledPatterns = dataTypes.flatMap { lgSize =>
    tileGrid.map(x => new Swizzled(x, lgSize))
  }

  val randomPatterns = dataTypes.flatMap { lgSize =>
    Seq(0, 1).map(new RandomAccess(0, 131072 >> lgSize, _))
  }

  def smemPatterns(clusterId: Int, size: Int = 128 << 10) = {
    Seq(("r", (x: TrafficPattern) => x.getSmem _),
      ("w", (x: TrafficPattern) => x.putSmem _))
      .flatMap { case (suffix, func) =>

      Seq(
        // stridedPatterns,
        // randomPatterns,
        // tiledPatterns,
        // tiledPatterns.map(Transposed(_)),
        swizzledPatterns,
        swizzledPatterns.map(Transposed(_)),
      )
        .flatten
        .map(Bounded(_, size))
        .map(x => (s"${x.name}_$suffix", func(x)(clusterId)))
    }
  }
}
