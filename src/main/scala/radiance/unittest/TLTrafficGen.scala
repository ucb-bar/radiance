package radiance.unittest

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import radiance.memory.SourceGenerator

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

case class ScalaTLA(
  address: BigInt,
  lgSize: Int,
  data: Option[BigInt], // none -> get, some -> put
  mask: Option[Int]
) {
  def toChisel(params: TLBundleParameters): ChiselTLA = {
    new ChiselTLA(params).Lit(
      _.address -> address.U,
      _.lgSize -> lgSize.U,
      _.hasData -> data.isDefined.B,
      _.hasMask -> mask.isDefined.B,
      _.data -> data.fold(0.U)(_.U),
      _.mask -> mask.fold(0.U)(_.U)
    )
  }
}

object ScalaTLA {
  def apply(address: BigInt, lgSize: Int) =
    new ScalaTLA(address, lgSize, None, None)

  def apply(address: BigInt, lgSize: Int, data: BigInt) =
    new ScalaTLA(address, lgSize, Some(data), None)

  def apply(address: BigInt, lgSize: Int, data: BigInt, mask: Int) =
    new ScalaTLA(address, lgSize, Some(data), Some(mask))
}

class ChiselTLA(params: TLBundleParameters) extends Bundle {
  val address = UInt(params.addressBits.W)
  val lgSize = UInt(params.sizeBits.W)
  val data = UInt(params.dataBits.W)
  val hasData = Bool()
  val mask = UInt((params.dataBits / 8).W)
  val hasMask = Bool()
}

class TLTrafficGen(val nodeName: String, val sourceBits: Int,
                   val n: Int, val reqFn: Int => ScalaTLA)
                  (implicit p: Parameters) extends LazyModule {

  val node = TLClientNode(Seq(TLMasterPortParameters.v2(
    masters = Seq(TLMasterParameters.v2(
      name = nodeName,
      sourceId = IdRange(0, 1 << sourceBits)
    )),
  )))

  lazy val module = new TLTrafficGenImp(this)
}

class TLTrafficGenImp(outer: TLTrafficGen) extends LazyModuleImp(outer) {

  // elaboration time
  // ================
  println(s"generating trace for ${outer.nodeName}")

  val (tlNode, tlEdge) = outer.node.out.head
  val storage = StoredBitVec(
    outer.nodeName,
    outer.n,
    new ChiselTLA(tlNode.params),
    Seq.tabulate(outer.n)(outer.reqFn).map(_.toChisel(tlNode.params))
  )

  // run time
  // ========

  val io = IO(new Bundle {
    val started = Input(Bool())
    val finished = Output(Bool())
  })

  val sourceGen = Module(new SourceGenerator(outer.sourceBits))
  val (counter, ended) = Counter(tlNode.a.fire, outer.n)

  val finishedReg = RegEnable(true.B, false.B, ended)

  storage.io.addr := counter
  val storedReq = storage.io.data

  tlNode.a.bits := Mux(
    storedReq.hasData,
    Mux(
      storedReq.hasMask,
      tlEdge.Put(sourceGen.io.id.bits, storedReq.address, storedReq.lgSize,
        storedReq.data, storedReq.mask)._2,
      tlEdge.Put(sourceGen.io.id.bits, storedReq.address, storedReq.lgSize,
        storedReq.data)._2
    ),
    tlEdge.Get(sourceGen.io.id.bits, storedReq.address, storedReq.lgSize)._2,
  )

  sourceGen.io.gen := tlNode.a.ready && !finishedReg
  tlNode.a.valid := io.started && sourceGen.io.id.valid && !finishedReg

  tlNode.d.ready := true.B
  sourceGen.io.reclaim.valid := tlNode.d.fire
  sourceGen.io.reclaim.bits := tlNode.d.bits.source

  io.finished := finishedReg && !sourceGen.io.inflight

  dontTouch(tlNode.d)
}

object StoredBitVec {
  def apply[T <: Bundle](
    filename: String,
    depth: Int,
    dataT: T,
    sequence: => Seq[T]
  )(implicit p: Parameters): StoredBitVec[T] = {
    val filePath = Paths.get(s"./generators/radiance/src/test/resources/traffic/${filename}.mem")
    if (!Files.exists(filePath)) {
      val parentDir = filePath.getParent
      if (parentDir != null) {
        Files.createDirectories(parentDir)
      }

      val writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)
      try {
        sequence.iterator.foreach { record =>
          val hexValue = record.litOption.getOrElse(BigInt(0)).toString(16) // dontcares -> 0
          writer.write(hexValue)
          writer.newLine()
        }
      } finally {
        writer.close()
      }
    }

    Module(new StoredBitVec(filePath, depth, dataT))
  }
}

class StoredBitVec[T <: Bundle](filePath: Path, depth: Int, dataT: T)
                               (implicit p: Parameters) extends Module {
  private val addrWidth = log2Ceil(depth)

  private val bb = Module(new StoredBitVecBlackBox(
    filePath.toAbsolutePath.toString, addrWidth, depth, dataT.getWidth
  ))
  val io = IO(new Bundle {
    val addr = Input(UInt(addrWidth.W))
    val data = Output(dataT.cloneType)
  })
  bb.io.addr := io.addr
  io.data := bb.io.data.asTypeOf(dataT.cloneType)
}

class StoredBitVecBlackBox(filePath: String, addrWidth: Int, seqLength: Int, dataWidth: Int)
  extends BlackBox(Map(
    "FILE_PATH" -> filePath,
    "ADDR_WIDTH" -> addrWidth,
    "DATA_WIDTH" -> dataWidth,
    "SEQ_LENGTH" -> seqLength,
  )) with HasBlackBoxInline {

  val io = IO(new Bundle {
    val addr = Input(UInt(addrWidth.W))
    val data = Output(UInt(dataWidth.W))
  })
  val moduleName = this.getClass.getSimpleName

  setInline(s"$moduleName.v",
    s"""
       |module $moduleName #(parameter FILE_PATH="", ADDR_WIDTH=0, DATA_WIDTH=0, SEQ_LENGTH=0) (
       |    input  [ADDR_WIDTH-1:0] addr,
       |    output [DATA_WIDTH-1:0] data);
       |
       |    logic [DATA_WIDTH-1:0] stimuli [0:SEQ_LENGTH-1];
       |    initial begin
       |        $$readmemh(FILE_PATH, stimuli);
       |    end
       |
       |    assign data = stimuli[addr];
       |endmodule
       |""".stripMargin)
}
