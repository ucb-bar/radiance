package radiance.memory

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.diplomacy.{AddressSet, TransferSizes}
import freechips.rocketchip.subsystem.{Attachable, HierarchicalElementPortParamsLike, TLBusWrapperLocation}
import freechips.rocketchip.tilelink._

/** Line-granular address hash over one aligned memory range, used to spread GPU memory over the
  * GPU L2 slices (and their DRAM channels) while the slices themselves own contiguous regions.
  *
  * Inside [base, base + size), an offset is split into a unit index v (offset / unitBytes) and a byte
  * within the unit. With s = log2(nSlices):
  *   o = v >> s                     the unit's index within its slice (dense)
  *   c = (v & (nSlices - 1)) ^ fold(o)   the slice, i.e. the top s offset bits of the physical address
  *   physical offset = c * size/nSlices + o * unitBytes + byte
  * fold(o) XORs the s-bit groups of o together. Consecutive units round-robin over the slices, each
  * slice's units stay dense, and a power-of-two stride does not stay on one slice because it changes
  * fold(o). The map is a bijection on the range and its inverse is v = (o << s) | (c ^ fold(o)).
  * Addresses outside the range are unchanged.
  *
  * Every agent that addresses this range through the coherent fabric must apply the same map
  * (AddressHashNode at its master port), and any loader that writes DRAM directly must too.
  */
case class AddressHashParams(base: BigInt, size: BigInt, unitBytes: Int, nSlices: Int) {
  require(isPow2(size) && (base & (size - 1)) == 0, f"hash range must be a power of two aligned to its size: $base%x/$size%x")
  require(isPow2(unitBytes) && isPow2(nSlices) && nSlices >= 2, s"unitBytes ($unitBytes) and nSlices ($nSlices) must be powers of two, nSlices >= 2")
  require(BigInt(unitBytes) * nSlices <= size, "range smaller than one unit per slice")

  val rangeBits: Int = log2Ceil(size)
  val unitBits: Int = log2Ceil(unitBytes)
  val sliceBits: Int = log2Ceil(nSlices)
  val unitIndexBits: Int = rangeBits - unitBits - sliceBits // width of o
  def range: AddressSet = AddressSet(base, size - 1)

  // ---- reference model (also the specification for loaders that write DRAM directly) ----
  private def foldModel(o: BigInt): BigInt = {
    var acc = BigInt(0); var x = o
    while (x != 0) { acc ^= x & (nSlices - 1); x >>= sliceBits }
    acc
  }
  def inRange(a: BigInt): Boolean = a >= base && a < base + size
  def forward(a: BigInt): BigInt = if (!inRange(a)) a else {
    val off = a - base
    val byte = off & (unitBytes - 1)
    val v = off >> unitBits
    val o = v >> sliceBits
    val c = (v & (nSlices - 1)) ^ foldModel(o)
    base + (c << (rangeBits - sliceBits)) + (o << unitBits) + byte
  }
  def inverse(a: BigInt): BigInt = if (!inRange(a)) a else {
    val off = a - base
    val byte = off & (unitBytes - 1)
    val c = off >> (rangeBits - sliceBits)
    val o = (off >> unitBits) & ((BigInt(1) << unitIndexBits) - 1)
    val k = c ^ foldModel(o)
    base + (((o << sliceBits) | k) << unitBits) + byte
  }

  // round-trip self-check of the model on a deterministic sample
  locally {
    val rnd = new scala.util.Random(1)
    val sample = (0 until 4096).map(_ => base + (BigInt(rangeBits, rnd) & (size - 1))) ++
      Seq(base, base + size - 1, base + unitBytes, base + BigInt(unitBytes) * nSlices)
    sample.foreach { a =>
      val f = forward(a)
      require(inRange(f) && inverse(f) == a, f"AddressHashParams model is not a bijection at $a%x")
    }
  }

  // ---- hardware ----
  private def fold(o: UInt): UInt =
    (0 until unitIndexBits by sliceBits).map { lo =>
      o(math.min(lo + sliceBits, unitIndexBits) - 1, lo).pad(sliceBits)
    }.reduce(_ ^ _)

  private def hit(a: UInt): Bool = (a >> rangeBits) === (base >> rangeBits).U

  /** Hash a client (virtual) address into the address the fabric routes on. */
  def forwardHW(a: UInt): UInt = {
    require(a.getWidth > rangeBits, s"address width ${a.getWidth} too small for the hash range")
    val byte = a(unitBits - 1, 0)
    val k = a(unitBits + sliceBits - 1, unitBits)
    val o = a(rangeBits - 1, unitBits + sliceBits)
    val c = k ^ fold(o)
    Mux(hit(a), Cat(a(a.getWidth - 1, rangeBits), c, o, byte), a)
  }

  /** Map a fabric address (e.g. in a probe) back to the client address. */
  def inverseHW(a: UInt): UInt = {
    require(a.getWidth > rangeBits, s"address width ${a.getWidth} too small for the hash range")
    val byte = a(unitBits - 1, 0)
    val o = a(rangeBits - sliceBits - 1, unitBits)
    val c = a(rangeBits - 1, rangeBits - sliceBits)
    val k = c ^ fold(o)
    Mux(hit(a), Cat(a(a.getWidth - 1, rangeBits), o, k, byte), a)
  }
}

/** The GPU memory hash; None disables it. Set by radiance.subsystem.WithGPUAddressHash. */
case object GPUAddressHashKey extends Field[Option[AddressHashParams]](None)

/** Applies an AddressHashParams on a TileLink edge: client addresses on A and C are hashed, manager
  * addresses on B are mapped back, so a client and its caches only ever see unhashed addresses.
  *
  * Managers in the hash range are advertised with transfers capped at one hash unit, since a
  * transfer that crossed a unit would be split over slices. Every manager overlapping the range must
  * lie entirely inside it, and every client visibility set must either contain the whole range or
  * miss it, so that the hash never moves an access across a boundary the parameters care about.
  */
class AddressHashNode(params: AddressHashParams)(implicit p: Parameters) extends LazyModule {
  private val unit = TransferSizes(1, params.unitBytes)
  private def cap(t: TransferSizes) = t.intersect(unit)

  val node = TLAdapterNode(
    clientFn = { c =>
      c.masters.foreach { m =>
        m.visibility.foreach { v =>
          require(!v.overlaps(params.range) || v.contains(params.range),
            s"AddressHashNode: client ${m.name} visibility $v covers only part of the hash range ${params.range}")
        }
      }
      c
    },
    managerFn = { mp =>
      mp.v1copy(managers = mp.managers.map { m =>
        if (!m.address.exists(_.overlaps(params.range))) m
        else {
          require(m.address.forall(a => params.range.contains(a)),
            s"AddressHashNode: manager ${m.name} straddles the hash range ${params.range}: ${m.address}")
          m.v1copy(
            supportsAcquireT   = cap(m.supportsAcquireT),
            supportsAcquireB   = cap(m.supportsAcquireB),
            supportsArithmetic = cap(m.supportsArithmetic),
            supportsLogical    = cap(m.supportsLogical),
            supportsGet        = cap(m.supportsGet),
            supportsPutFull    = cap(m.supportsPutFull),
            supportsPutPartial = cap(m.supportsPutPartial),
            supportsHint       = cap(m.supportsHint))
        }
      })
    })

  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out).foreach { case ((i, _), (o, _)) =>
      o.a <> i.a
      o.a.bits.address := params.forwardHW(i.a.bits.address)
      i.b <> o.b
      i.b.bits.address := params.inverseHW(o.b.bits.address)
      o.c <> i.c
      o.c.bits.address := params.forwardHW(i.c.bits.address)
      i.d <> o.d
      o.e <> i.e
    }
  }
}

object AddressHashNode {
  def apply(params: AddressHashParams)(implicit p: Parameters): TLNode =
    LazyModule(new AddressHashNode(params)).node
}

/** A tile or cluster master port that applies the address hash on the interconnect side of
  * whatever the wrapped port params inject (buffers, cork). */
case class HashedMasterPortParams(inner: HierarchicalElementPortParamsLike, hash: AddressHashParams)
    extends HierarchicalElementPortParamsLike {
  def where: TLBusWrapperLocation = inner.where
  def injectNode(context: Attachable)(implicit p: Parameters): TLNode =
    AddressHashNode(hash) :=* inner.injectNode(context)
}
