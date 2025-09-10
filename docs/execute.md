# Execute

## Overview

The execute stage consists of multiple execution pipelines:
* Floating point units
* Integer units
* Branch units
* Special function units

The LSU is discussed separately.

Special function units cover:
* Control status registers (CSRs)
* Synchronization hardware
* Potential: accelerator control

It also contains interfacing logic with issue and writeback.


This is a stub. I'll fill this in, but I'm pushing it now to avoid duplicate
work.

Points to cover:
* non 1:1 FU mapping
* writeback arbitration
* FU pipelining
* mixed precision FPU
* fp exceptions and rounding modes
* forwarding support
* predicated execution
  * at least we should have a hacked single thread mode
* area estimation
* pc relative execution
