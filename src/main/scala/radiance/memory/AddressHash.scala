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
  * With channelsPerSlice > 1 the nSlices parts are DRAM channels, channelsPerSlice of them per L2
  * slice, and the physical part of c is (c mod S) * channelsPerSlice + c / S (S = nSlices /
  * channelsPerSlice): the low bits of c pick the L2 slice, so consecutive units still round-robin
  * over the slices, and the high bits pick the channel within it. The slice's channels stay
  * adjacent, so each slice still owns one contiguous region.
  * chanSetStride (units, a power of two) XORs channel j's in-part index o with j * chanSetStride.
  * Without it, the units v and v + S of a stream (same o, different channel) reach one slice back to
  * back with the same L2 set index, and the L2 serializes requests to one set. Set it to the
  * slice's L2 set count divided by channelsPerSlice; 0 disables it.
  *
  * Every agent that addresses this range through the coherent fabric must apply the same map
  * (AddressHashNode at its master port), and any loader that writes DRAM directly must too.
  */
case class AddressHashParams(base: BigInt, size: BigInt, unitBytes: Int, nSlices: Int, channelsPerSlice: Int = 1,
                             chanSetStride: Int = 0) {
  require(isPow2(size) && (base & (size - 1)) == 0, f"hash range must be a power of two aligned to its size: $base%x/$size%x")
  require(isPow2(unitBytes) && isPow2(nSlices) && nSlices >= 2, s"unitBytes ($unitBytes) and nSlices ($nSlices) must be powers of two, nSlices >= 2")
  require(BigInt(unitBytes) * nSlices <= size, "range smaller than one unit per slice")
  require(isPow2(channelsPerSlice) && channelsPerSlice <= nSlices,
    s"channelsPerSlice ($channelsPerSlice) must be a power of two no larger than nSlices ($nSlices)")

  val rangeBits: Int = log2Ceil(size)
  val unitBits: Int = log2Ceil(unitBytes)
  val sliceBits: Int = log2Ceil(nSlices)
  val unitIndexBits: Int = rangeBits - unitBits - sliceBits // width of o
  val chanBits: Int = log2Ceil(channelsPerSlice)
  private val l2Slices: Int = nSlices / channelsPerSlice
  require(chanSetStride == 0 || (isPow2(chanSetStride) &&
    BigInt(chanSetStride) * channelsPerSlice <= (BigInt(1) << unitIndexBits)),
    s"chanSetStride ($chanSetStride) must be 0 or a power of two with chanSetStride * channelsPerSlice <= 2^$unitIndexBits")
  private val strideBits: Int = if (chanSetStride == 0) 0 else log2Ceil(chanSetStride)
  def range: AddressSet = AddressSet(base, size - 1)

  // ---- reference model (also the specification for loaders that write DRAM directly) ----
  private def foldModel(o: BigInt): BigInt = {
    var acc = BigInt(0); var x = o
    while (x != 0) { acc ^= x & (nSlices - 1); x >>= sliceBits }
    acc
  }
  private def placeModel(c: BigInt): BigInt = (c % l2Slices) * channelsPerSlice + c / l2Slices
  private def unplaceModel(p: BigInt): BigInt = (p % channelsPerSlice) * l2Slices + p / channelsPerSlice
  private def flipModel(c: BigInt): BigInt = (c / l2Slices) * chanSetStride  // channel j -> j * stride
  def inRange(a: BigInt): Boolean = a >= base && a < base + size
  def forward(a: BigInt): BigInt = if (!inRange(a)) a else {
    val off = a - base
    val byte = off & (unitBytes - 1)
    val v = off >> unitBits
    val o = v >> sliceBits
    val c = (v & (nSlices - 1)) ^ foldModel(o)
    base + (placeModel(c) << (rangeBits - sliceBits)) + ((o ^ flipModel(c)) << unitBits) + byte
  }
  def inverse(a: BigInt): BigInt = if (!inRange(a)) a else {
    val off = a - base
    val byte = off & (unitBytes - 1)
    val c = unplaceModel(off >> (rangeBits - sliceBits))
    val o = ((off >> unitBits) & ((BigInt(1) << unitIndexBits) - 1)) ^ flipModel(c)
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

  // part c -> physical part and back: a rotation of the sliceBits-wide part number by chanBits
  private def place(c: UInt): UInt =
    if (chanBits == 0 || chanBits == sliceBits) c
    else Cat(c(sliceBits - chanBits - 1, 0), c(sliceBits - 1, sliceBits - chanBits))
  private def unplace(p: UInt): UInt =
    if (chanBits == 0 || chanBits == sliceBits) p
    else Cat(p(chanBits - 1, 0), p(sliceBits - 1, chanBits))
  // XOR channel j (the top chanBits of c) into o at bit strideBits
  private def flip(c: UInt): UInt =
    if (chanSetStride == 0 || chanBits == 0) 0.U(unitIndexBits.W)
    else (c(sliceBits - 1, sliceBits - chanBits) << strideBits).pad(unitIndexBits)

  /** Hash a client (virtual) address into the address the fabric routes on. */
  def forwardHW(a: UInt): UInt = {
    require(a.getWidth > rangeBits, s"address width ${a.getWidth} too small for the hash range")
    val byte = a(unitBits - 1, 0)
    val k = a(unitBits + sliceBits - 1, unitBits)
    val o = a(rangeBits - 1, unitBits + sliceBits)
    val c = k ^ fold(o)
    Mux(hit(a), Cat(a(a.getWidth - 1, rangeBits), place(c), o ^ flip(c), byte), a)
  }

  /** Map a fabric address (e.g. in a probe) back to the client address. */
  def inverseHW(a: UInt): UInt = {
    require(a.getWidth > rangeBits, s"address width ${a.getWidth} too small for the hash range")
    val byte = a(unitBits - 1, 0)
    val c = unplace(a(rangeBits - 1, rangeBits - sliceBits))
    val o = a(rangeBits - sliceBits - 1, unitBits) ^ flip(c)
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
