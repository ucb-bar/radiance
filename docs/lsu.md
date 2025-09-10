## General Notes
- Memory system will not support unaligned accesses (N-byte loads must be to addresses divisible by N)
	- CUDA has same limitation
- Memory ordering will be very relaxed - loads and stores in one thread can appear in any order to another thread
- Coherence is also relaxed
	- L1 is write-through, and L2 is system-wide / coherence hub
	- L2 will not invalidate private (L1) lines, so it is possible to read stale data across threadblocks
		- Caches flushed on kernel boundaries
## Load / Store Queue
- other designs in handwritten PDF
### Design 2.5: Keep stores in program order, allow reordering consecutive loads
   ![[fig/ld_st_queue.png]]
   - Slots in LDQ/STQ are allocated in program order from decode stage
   - LDQ entries contain the tail pointer of STQ from when they were allocated, and vise-versa
   - Loads not allowed to begin until head of STQ reaches the stored pointer (i.e. all older stores retired)
	   - Loads not only issued from head of queue (allows reordering consecutive loads)
   - Stores not allowed to begin until head of LDQ reaches stored pointer, and it is at the head of STQ (all older loads and stores retired)
   - Design forces program order, allows sizing the two queues differently

## Address Switch
![[fig/addressing.png]]
- Comparators to check if address is in Shared Memory or Global address space
- Note: ISA limitations mean you can technically make loads to shared / global addresses at the same time
	- This is very undefined - should we fault?
	- Ideal - some extra bits in instruction encoding (e.g. ld.global, ld.shared)
- Probably want separate LDQ/STQ for Shared / Global to eliminate false ordering between

## Shared Memory
![[fig/shared_memory.png]]
- 16 banks (matching 16 threads), word interleaved - fast in contiguous access case
- Bank scheduler handles serialization of bank conflicts
- A request goes through 2 stages:
	1. Decode + Broadcast Detection + Conflict Detection
	   
	   LSB = bank index, MSBs = address within bank
	   
	   For reads, check if it's a broadcast (all addresses equal) - we special case this to avoid all threads serializing
	   
	   ![[fig/conflict.png]]
	   Conflict detection diagram
	   
	   Track serviced lanes, continue until all lanes are serialized. We don't attempt to discover more bank parallelism between different requests (i.e. no coalescing)
	2. Data Read/Write
	   
	   Read/Write into 1R1W SRAM
### Atomics Support
- Not sure how to efficiently support lr / sc
	- Do we need to?
- AMOADD, AMOSUB, ... are much easier by comparison - just attach 1 ALU per bank and schedule Read-Modify-Write sequence appropriately

## Global Memory Path

TODO: does HellaCache / SiFive L2 support miss merging? This is probably a critical optimization for GPU memory system

TODO: investigate how atomics work with TL - probably need direct 