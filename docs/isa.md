Muon ISA
========

Muon ISA is an extended RISC-V ISA that enables writing kernels that
reflect performance characteristics of a modern GPU hardware with minimal
ISA-level overhead.

Its key features are:

* 64-bit instruction encoding, which allows for:
* **Up to 256 architectural registers** per thread, with microarchitecture
  support for dynamic warp occupancy;
* **Up to 4 source operands and 1 destination operand** per instruction;
* Support for **predicated execution**;
* **Extended immediate and opcode fields**.


## Motivation

RISC-V ISA is originally designed for CPUs that has many architectural
incompatibilities to GPU programming.  Some of its limitations include:

* **Small register space** of 32 integer and 32 floating-points prevent
  allocating a high number of registers per-thread.  This creates two problems:
  * Limited support for **dynamic warp occupancy** that allows the programmer
    to trade thread-level parallelism for increased instruction-level
    parallelism.  This mechanism is extensively used in modern GEMM kernels to
    allow better intra-warp data reuse and increase Tensor Core utilization.
  * **Increased register spills to the stack.**  GPU kernels heavily rely on
    loop unrolling and code inlining to increase ILP and reduce
    branching/function call overheads.  RISC-V's small register space induces
    frequent spills to the stack memory, which has high overhead in the GPU
    memory system.
* **No support for predicated execution** limits the ISA to always rely on
  explicit branch divergence/reconvergence instructions, which incurs high
  instruction slot overhead for small branches.
* **Limited number of opcodes and operand fields** limits mapping specialized
  operations efficiently to the ISA, e.g. Tensor Core operations with
  register-pair operands and graphics operations on quad primitives.  This
  leads to fragmenting an operation to multiple instruction slots, which incurs
  high cost in the SIMT frontend.

These limitations of RISC-V motivate Muon ISA's key features stated above.


## Instruction Format

### R-type
R-type instructions will include current RISC-V R-type instructions, as well as R4-type instructions used by Vortex and floating point instructions. In particular, the `funct2` field in R4 will be delegated to the last two bits of `funct7`, with the upper 5 bits set to 0. It has this format:
```
63  60   59   58    52 51 44 43 36 35 28 27 20 19    17 16 9 8     7 6      0
[pred] [resv] [funct7] [rs4] [rs3] [rs2] [rs1] [funct3] [rd] [opext] [opcode]
  4b     1b      7b      8b    8b    8b    8b     3b     8b     2b      7b
```
#### Subtypes
* R5 has `rs1` through `rs4`, as well as `rd`
	* Assembly: `opcode.variant rd, rs1, rs2, rs3, rs4 @ pred`
* R4 has `rs1` through `rs3`, as well as `rd`
	* R4Frm used for floating points, with `frm` as `funct3`
	* Assembly: `opcode.variant rd, rs1, rs2, rs3 @ pred`
* R3 has `rs1` and `rs2`, as well as `rd` (this is what base RISC-V R-type is)
	* R3Atomic for atomic, with `aq`, `rl`, `funct5` as `funct7`
	* R3Frm for floating point
	* Assembly: `opcode.variant rd, rs1, rs2 @ pred`

**Special case for floating point operations.** The floating point rounding mode `frm` will remain located at `funct3`.
### I3-type
I3-type instructions will have up to 2 source registers and up to 1 destination register. This leaves 24 bits for immediate, which can be used directly or split across the two source registers as offsets.
```
63  60 59         36 35 28 27 20 19    17 16 9 8     7 6      0
[pred] [ imm[23:0] ] [rs2] [rs1] [funct3] [rd] [opext] [opcode]
  4b        24b        8b    8b     3b     8b     2b      7b
```
#### Subtypes
* I3 has `rs1`, `rs2`, `rd`, and a 24-bit immediate
	* Assembly: `opcode.variant rd, rs1, rs2, imm @ pred` 
* II3 has `rs1`, `rs2`, `rd`, and two 12-bit immediates
	* Assembly: `opcode.variant rd, imm1(rs1), imm2(rs2) @ pred` 

### S/SB-type
S/SB-type instructions has 2 source registers. The upper 8 bits of the 32-bit immediate is encoded in rd.
```
63  60 59         36 35 28 27 20 19    17 16           9 8     7 6      0
[pred] [ imm[23:0] ] [rs2] [rs1] [funct3] [ imm[31:24] ] [opext] [opcode]
  4b        24b        8b    8b     3b          8b          2b      7b
```
* S and SB both have `rs1`, `rs2`, and a 32-bit immediate
	* S Assembly: `opcode.variant rs2, imm(rs1) @ pred`
	* SB Assembly: `opcode.variant rs1, rs2, label @ pred`

**Special case for SB instructions.** Base RISC-V branch PC offset has implicit bit 0. For Muon, bits 0 to 2 are explicitly encoded as 0s in the machine code (bit 36 to 38), leaving 29 remaining effective bits. The assembly behavior remains the same (immediate is specified in number of bytes), but the specified value gets rounded down to a multiple of 8.
### I2-type
I1-type instructions will have 1 source register and 1 destination register. This leaves the full 32bits for immediate. This instruction will replace the `I` and `UJ` type instructions in the original RISC-V specification, and will render `U` type instructions unnecessary.
```
63  60 59         36 35          28 27 20 19    17 16 9 8     7 6      0
[pred] [ imm[23:0] ] [ imm[31:24] ] [rs1] [funct3] [rd] [opext] [opcode]
  4b        24b            8b         8b     3b     8b     2b      7b
```
#### Subtypes
* I2 has `rs1`, `rd`, and a 32-bit immediate (base RISC-V I-type)
	* Assembly: `opcode.variant rd, rs1, imm @ pred`
* U and J both have `rd` and a 32-bit immediate.
	* U should no longer be necessary but is preserved for compatibility.
		* `auipc` will be unnecessary since branch offsets can be 32-bit;
		* `lui` will be unnecessary since the immediate for `addi` can be 32-bit;
	* U Assembly: `opcode.variant rd, imm @ pred`
	* J Assembly: `jal rd, label @ pred`

**Special case for J instruction.** Similar to branches, implicit bit 0 is now explicit zeros for bits 0 to 2.
**Special case for shift-immediate instructions.** Shift amount remains `imm[6:0]`. Shift opcode will still occupy the same bits in the immediate (`[11:7]`); however it will no longer overlap with where `funct7` is in R-types. 
**Special case for CSR instructions.** The CSR source/dest used to be encoded in the imm12 field; it's now 32-bits (nice and wide, as it should be). The CSR immediate used to be encoded in the 5-bit rs1 address; it will still occupy rs1 in Muon but will expand to use 8 bits.

### Neutrino Instructions
```
63  60 59               54 53  52 51  44 43  36 35  28 27  20 19    18   17   16 9 8     7 6           0 
[pred] [ elem_count[5:0] ] [part] [dep2] [dep1] [dep0] [task] [retire] [sync] [rd] [opext] [op_neutrino]
```

Free opcodes:  
Custom 0~3: `0b 2b 5b 7b`  
Others: `1f 3f 5f 6b 77 ff`

**`opext`** specifies the type of neturino instruction.

* `2'b00` is `nu.invoke`;
* `2'b01` is `nu.payload`;
* `2'b10` is `nu.complete`;
* `2'b11` is reserved.

**`retire`**: specifies the retirement mode.

* `2'b00` is immediate retirement, and is represented with suffix `*.ir*`;
* `2'b01` is hardware retirement (task specific), suffix `*.hr`;
* `2'b10` is manual retirement `*.mr`;
* `2'b11` is reserved.

**`part`**: specifies the participation mode.

* `2'b00` waits for `elem_count + 1` threads, and is represented with suffix `*.pt`;
* `2'b01` is warps, suffix `*.pw`;
* `2'b10` is cores, sufffix `*.pc`;
* `2'b11` is reserved.

Note: `elem_count` stores the number of participants plus one, so the maximum representable value is 256. Minimum is 1 (since at least one thread will execute this instruction). When specifying this value in assembly, there's no need to subtract one as it's handled by the compiler. It is also an immediate, so the value needs to be known beforehand.

**`dep0`** through **`dep2`** specifies the jobs the invocation depends on. Format:
```
31         24 23      0
[pipe_prefix] [counter]
```

**`task`** is the register address that holds the task ID to invoke. Value of zero means it's a dummy invocation.

**`sync`** when set means the invocation is synchronous, async otherwise.

**Assembly mnemonic**
* Payload: `nu.payload /*task id=*/t1, /*payload0=*/a0, /*payload1=*/a1, /*payload2=*/a2`
* Invoke: `nu.invoke.mr.pt.async /*rd=*/t1, /*task id=*/MATMUL, /*dep0=*/t0, /*dep1*/zero, /*dep2=*/zero, /*num_elems=*/1`
* Complete: `nu.complete.pt /*job id=*/t1, /*num_elems=*/1`

## New Registers

At 128 registers, we have 96 additional registers to allocate.
There will be 16 additional `a` registers, for a total of 24
There will be 32 additional `t` registers, for a total of 39
There will be 48 additional `s` registers, for a total of 60

For now we double the number of floating point registers to 64, with each type having the same share of the new 32 (i.e. 8 `a` regs, 12 `s` regs, 12 `t` regs).

