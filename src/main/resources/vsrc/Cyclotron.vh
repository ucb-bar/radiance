// shared Cyclotron DPI declarations
// whenever you change these declarations, make sure to update:
// (1) import "DPI-C" declaration
// (2) C function declaration
// (3) Verilog DPI calls inside initial/always blocks

import "DPI-C" function void cyclotron_init(input string elffile);

import "DPI-C" function string vpi_get_binary();

import "DPI-C" function cyclotron_difftest_reg(
  input bit  trace_valid,
  input int  trace_pc,
  input bit  trace_regs_0_enable,
  input byte trace_regs_0_address,
  input int  trace_regs_0_data[NUM_LANES],
  input bit  trace_regs_1_enable,
  input byte trace_regs_1_address,
  input int  trace_regs_1_data[NUM_LANES],
  input bit  trace_regs_2_enable,
  input byte trace_regs_2_address,
  input int  trace_regs_2_data[NUM_LANES]
);

import "DPI-C" function void cyclotron_imem(
  output bit     imem_req_ready,
  input  bit     imem_req_valid,
  input  byte    imem_req_bits_store,
  input  int     imem_req_bits_address,
  input  byte    imem_req_bits_size,
  input  byte    imem_req_bits_tag,
  input  longint imem_req_bits_data,
  input  byte    imem_req_bits_mask,
  input  bit     imem_resp_ready,
  output bit     imem_resp_valid,
  output byte    imem_resp_bits_tag,
  output longint imem_resp_bits_data
);

