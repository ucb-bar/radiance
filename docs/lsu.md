## General Notes
- Memory system will not support unaligned accesses (N-byte loads must be to addresses divisible by N)
	- CUDA has same limitation
- Memory ordering will be very relaxed - loads and stores in one thread can appear in any order to another thread
- Coherence is also relaxed; without explicit flushes (common case: kernel boundary), different threadblocks have no guarantee of coherent view to memory
- ISA modification: we introduce address space qualifier for loads and stores to distinguish global from shared memory; this requires some compiler changes but LLVM certainly supports it (worst case, use intrinsics for shared memory)
- Below details are written by AI and checked by me

## Architecture
The Load/Store Unit (LSU) handles memory instructions (loads, stores, atomics, fences) from the core. 

### Overall Structure
- **Per-Warp Queues:** Each warp has four independent circular FIFO queues:
  - Global Load Queue (LDQ)
  - Global Store Queue (STQ)
  - Shared Load Queue (LDQ)
  - Shared Store Queue (STQ)
  These queues track outstanding memory operations and enforce program order for stores and atomics.

- **Queue Organization:**
  - Each queue is implemented as a circular buffer with physical and logical head/tail pointers.
  - Physical head tracks deallocation (when entries are freed), logical head tracks retirement (when operations are complete and unblock future memory requests that were waiting on it for ordering purposes).
  - Each entry in a queue contains:
    - Valid bit
    - Operation type (load, store, atomic, fence)
    - Operand readiness
    - Issued status (whether a memory request has been sent)
    - Address and store data indices (SRAM pointers)
    - Load data index (SRAM pointer, for loads and atomics)
    - Hazard tracking info (snapshot of other queue's tail)
    - Done and writeback flags

- **Hazard Tracking:**
  - Each load queue tracks the tail of the corresponding store queue at allocation time, enabling detection of RAW hazards (loads must wait for older stores to retire).
  - Store queues similarly track the load queue for correct ordering.
  - The system uses the MSB of the difference between head/tail pointers to detect wraparound and determine if older operations have retired.

- **Resource Allocation:**
  - Free list allocators manage indices for address/tmask SRAM, store data SRAM, and load data SRAM.
  - Address and store data indices are allocated at queue reservation time to avoid deadlock.
  - Load data indices are allocated at memory request issue time 

- **SRAMs:**
  - Address/tmask SRAM: Stores addresses and lane masks for each memory operation.
  - Store data SRAM: Stores data for store operations.
  - Load data SRAM: Stores data returned from memory for loads and atomics.
  - PReg/tmask SRAM: Stores destination register and tmask for writeback.

- **Data and Control Flow:**
  1. **Reservation:**
     - The IBUF stage issues a reservation to the LSU for each memory instruction, specifying address space and operation type.
     - The LSU allocates a queue slot and SRAM indices, returning a token that is ultimately stored in the LSU-local reservation station.
  2. **Operand Collection:**
     - When operands are ready, the reservation station issues them to the LSU, which writes address/tmask and store data to SRAM.
     - The queue entry (found using token) is marked as ready for issue.
  3. **Request Issue:**
     - The queue checks for hazards and readiness, then issues a memory request when safe.
     - Loads wait for older stores to retire; stores issue strictly in program order.
     - Atomics and fences are treated as stores for purposes of ordering.
  4. **Memory Response:**
     - When memory responds, the LSU updates the queue entry, writes load data to SRAM, and marks the entry for writeback (loads/atomics) or retirement (stores/fences).
  5. **Writeback:**
     - The LSU arbitrates writeback requests, reads data from SRAM, and returns results to the core.
     - Entries are deallocated after writeback or retirement.

- **Arbitration:**
  - Memory requests from all queues are arbitrated using a priority scheme:
    - Shared > Global
    - Loads > Stores
    - Lower warpId > higher warpId
  - Only one request per cycle is issued to the memory subsystem.
  - Writeback requests are similarly arbitrated, prioritizing shared loads/atomics over global loads/atomics.

- **Flush and Synchronization:**
  - The LSU provides an `empty` signal indicating all queues are empty, used for flushing at kernel boundaries or threadblock synchronization.

### Memory Ordering and Relaxation
- Only consecutive loads within the same warp are allowed to be reordered
- Atomics and fences enforce ordering by being treated as stores.

## Parameters

### LoadStoreUnitParams
- `numLsuLanes`: Number of lanes for memory interface and writeback (default: 16)
- `numGlobalLdqEntries`: Max global load instructions per warp (default: 8)
- `numGlobalStqEntries`: Max global store instructions per warp (default: 4)
- `numSharedLdqEntries`: Max shared load instructions per warp (default: 4)
- `numSharedStqEntries`: Max shared store instructions per warp (default: 2)
- `loadDataEntries`: Max in-flight/pending load requests (default: 16)
- `storeDataEntries`: Max unissued store requests (default: 8)
- `addressEntries`: Max unissued memory requests (default: 16)

### Derived Parameters
- `multiCycleWriteback`: True if numLanes > numLsuLanes
- `numPackets`: Number of packets needed to process full vector (numLanes / numLsuLanes)
- `sourceIdBits`: Bits needed for request tags (LsuQueueToken width + packetBits)

## Core Interface

### Core Requests (LsuRequest)
- `token`: Queue entry identifier (warpId, addressSpace, ldq, index)
- `tmask`: Vector lane mask
- `address`: Vector of lane addresses
- `imm`: Immediate offset for address generation
- `destReg`: Destination register
- `storeData`: Vector of data to store

### Core Response (LsuResponse)
- `warpId`: Destination warp
- `packet`: Packet index for multi-cycle writeback
- `tmask`: Vector lane mask
- `destReg`: Destination register
- `writebackData`: Vector of load results

### Core Reservations
- `coreReservations`: Vector of reservation interfaces per warp
  - Contains addressSpace, operation type from decode

### Status
- `empty`: Indicates if all queues are empty, used for flushing LSU at kernel boundaries and threadblock synchronization

## Memory Interface

### Memory Requests (LsuMemRequest)
- `globalMemReq`: Global memory request interface
- `shmemReq`: Shared memory request interface
  - Both carry: tag, operation type, address vector, data vector, and tmask

### Memory Responses (LsuMemResponse)
- `globalMemResp`: Global memory response interface
- `shmemResp`: Shared memory response interface
  - Both carry tag and data vectors

## Area Estimation
For default parameters (16 lanes, 8 warps):

### Flip-flops:
- LSQ: ~25 bits/entry, 18 entries per warp, ~3600 bits total
- Free list allocators: ~300 total
- Request/response buffers: 3 * (512 bits) = 1536 bits
- Additional: maybe 10%
Total FF estimate: ~6000 bits

### SRAMs:
- Address/tmask: 16 entries * (16 * 32 + 16) bits ≈ 8Kb
- Store data: 8 entries * 16 * 32 bits ≈ 4Kb
- Load data: 16 entries * 16 * 32 bits ≈ 8Kb
- PReg/tmask: 144 entries * (16 + 7) bits ≈ 0.5Kb
Total SRAM: ~20.5Kb