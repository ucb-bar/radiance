The Muon ISA will be a modified RISC-V ISA that is targeted towards graphics usage.

Key features are:
* 64-bit instead of 32-bit instruction words. No mixed instruction sizes;
* Up to 4 source operands and 1 destination operand can fit in a single instruction;
* Support for a predicate register field;
* Much larger immediate number;
* Extended opcode space.

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

## New Registers

At 128 registers, we have 96 additional registers to allocate.
There will be 16 additional `a` registers, for a total of 24
There will be 32 additional `t` registers, for a total of 39
There will be 48 additional `s` registers, for a total of 60

For now we double the number of floating point registers to 64, with each type having the same share of the new 32 (i.e. 8 `a` regs, 12 `s` regs, 12 `t` regs).


## TODO list

- [x] Define all instruction types
- [x] RISCVAsmParser to parse immediates
- [x] Instruction type IDs in InstrFormats
- [x] RVInst definitions
- [x] Immediate generation logic
- [ ] Fixups
	- [x] I2 fixups
	- [x] I3 fixups <- make sure upper 12-bit works
	- [ ] call fixup, make sure auipc gets the right value
- [x] CSR instructions format
- [x] ExpandPseudoInsts to eliminate `lui` and `auipc` insertion logic
	- [x] lui
	- [x] auipc (cant really eliminate, esp. with linker relocation)
- [x] Define the new registers
- [x] Alias definitions
- [x] Node patterns
- [x] Frame lowering (calling convention) register saving
- [x] Register allocation priority
- [x] Disassembler
- [x] AsmParser
- [x] AsmWriter
- [x] Get the whole thing to compile
- [ ] Basic ISA emulator
	- [x] Fetch
	- [x] Decode
	- [x] Execute
	- [x] Memory
	- [x] Writeback
- [ ] Testing
	- [x] Basic syntax
	- [ ] Calling convention (register saving, argument passing, etc)
	- [ ] All immediates
	- [ ] Base rv32 ISA tests
	- [x] Directives
	- [x] Registers above 32, GPR and FP
	- [ ] PC-relative stuff
		- [ ] all kinds of jumps
		- [ ] pc relative loads
		- [ ] pc relative stores
		- [ ] function calls
- [ ] Long term goals
	- [ ] Implement support for I3 instructions
		- [ ] add a fixup
		- [ ] add node pattern match
		- [ ] 