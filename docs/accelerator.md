# Accelerator Control

Gemmini needs to be controllable by both the SIMT cores and host Rocket core.

## Gemmini MMIO Interface

We will reuse the Gemmini MMIO interface used in Virgo. The memory map of this
control scheme is the following:

Addr | Size (B) | Description
------------------------------------
`0x00`  | 4        | RoCC instruction word
`0x10`  | 4        | RoCC RS1 LSB (LE)
`0x14`  | 4        | RoCC RS1 MSB (LE)
`0x18`  | 4        | RoCC RS2 LSB (LE)
`0x1c`  | 4        | RoCC RS2 MSB (LE)
`0x20`  | 4        | Gemmini busy
`0x28`  | 4        | Gemmini # of running loops

## CISC Interface & CISC MMIO

### Supported operations

## Rocket Core Interface

## Scratchpad Memory Topology


