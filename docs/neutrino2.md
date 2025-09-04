# Generalized, Modular, Data-Tracking Neutrino

## Review of Neutrino V1

Neutrino tries to do two things:

1. **Simplify coarse grained control hazard management.**

Neutrino creates a higher hierarchy of *task level dependency graph* that is
constructed dynamically, with dependency checking implemented efficiently in
hardware using a Scoreboard.

2. **Reduce instruction throughput overhead for accelerator invocations.**

While reduced scalar synchronization instructions aid this process, the
intention was to also combine with a either a small dedicated scalar CPU or an
FSM to sequence through accelerator instructions.

### Deficiencies

1. **Dependency graph doesn't fully map well to some cases.** Specifically,
   multi-producer single-consumer is well supported under the current model,
   but multi-consumer is much more difficult. Multi-consumer however is a
   common pattern for work stealing situations.

2. **Data dependency is separately managed and often has to be tacked on.**
   Software pipelining with Neutrino is easy as long as there's no data
   involved. If each stage requries allocating data in the shared memory
   however, the memory space must be manually and statically partitioned by the
   programmer, which is very error-prone. Furthermore, there's no mapping from
   a task to a memory region.

3. **Neutrino front-end is highly tied to GPU warp scheduling.** The initial
   concept had Neutrino be able to directly stall GPU warp scheduling. This
   complicates the core concepts, increases hardware complexity for the warp
   scheduler as it's required to deal with an unpredictable external stall
   signal, while requiring many wires traveling to and from a central location
   (the scoreboard). It also does not provide a mechanism for non SIMT cores to
   schedule a task.

4. **Neutrino back-end is not generalizable.** Not all accelerators need a lot
   of CPU attention for configuration and invocation. Plus, the accelerator ISA
   tends to differ.

<!--
5. **Hard to specify more than 2 dependencies or add more later.** Dependency
   specification is restricted to the initial invocation; more dependencies
   must be specified as a tree with temporary intermediate nodes constructed in
   software. Invocation order is also more restricted this way.
-->

5. **The *task* confusingly multiplexes a namespace handler and a job
   description.** This is more minor. The task ID both can specify an
   accelerator job like matmul, as well as act as the category name for a
   coherent set of task instances. However, the problem arises when there are
   multiple streams of execution for the same job that needs to be independent
   in the graph. The solution to assign multiple IDs per category was clumsy.

## Neturino V2

Neutrino V2 tries to be a more purist design by distilling the task management
mechanism, and modularizing the front and back end.

The core has two types of ports: task schedule and task issue. Task schedule
ports connects the SIMT cores to the front end of the Neutrino Core. Task issue
ports connects the back end of the Neutrino Core to the accelerators.

### The Front End

Task schedule ports are bidirectional. The bundle going into Neutrino contains:

* **Task ID.** Statically assigned by a compiler, can be simply a wrapping
  counter.
* **Opcode.** Decoupled from the old task ID, this specifies the backend job
  description.
* **Retirement Mode** is as specified in Neutrino V1.

However, some prior fields are now removed from Neutrino's job duties. These
are:

* **Synchronicity.** This is just handled by the SIMT warp scheduler.
* **Participation Mode.** This will be a separate block aggregating the SIMT
  warps.

Futhermore, payload instructions will no longer be required. 

The bundle going out of the Front End contains:

* **Task Instance ID**. More on this later.

What the separation of concern allows is non SIMT cores to issue tasks. This
includes accelerators - think ray tracing units dispatching hits to the SIMT
cores; this also includes Neutrino itself - hierarchical dependency management.

### The Core

The scoreboard, along with the logic for loop carried dependencies, stays mostly
intact. However, we now incoporate memory management into the picture.

The Core contains a hardware memory allocator (malloc unit). We assume for now
that this allocator takes a size input and outputs an address, where a
contiguous free block of the given size exists in shared memory.

We maintain a map of task ID to size. This can be modified by the outside
through a port. The idea is each task will usually have the same memory
requirements for each iteration in a loop. The size value can be 0.

When we receive a task schedule request for a task ID, we check this table. If
the size is 0, we revert to old logic and return the instance ID that is the
concatenation of `task ID` and `counter`. If the size is nonzero however, we
first allocate a block in SMEM, and update an entry in the *block table*, which
is a construct similar to the *page table*. The block table maps instance ID to
physical addresses in the shared memory.

Consider what this allows us to do. Using these two tables, we can easily find
out where the data that corresponds to a task instance is, as well as the size
of the data block. When doing dependency checking, we automatically consider
data dependencies, because **the handle to a task is the exact same as the
handle to its data**. In other words, control and data are the same dependency.

#### Lifetimes

We now have to consider the lifetime of the data block. The naive idea would be
to tie the data allocation to the entry in the scoreboard; However, when an
entry is retired from the scoreboard, the data oftentimes must still be resident
for dependent tasks.

This leads to the concept of the *refcount* - the number of holds a block of
data has. A piece of data will be considered occupied until its refcount is
reduced to 0. Depedendent tasks will automatically increment the dependency
tasks' refcount when scheduled, and decrement it when retired.

In a similar lineage, the *depcount* specifies the number of control
dependencies expected. Issuance will be blocked until depcount reduces to 0.
This is beneficial in cases where data needs to be written to the allocated
block before it can be sent off.

Summarizing with the instructions:

* Invoke (set depcount, refcount)
* Complete (depcount - 1, refcount - 1)
* Control Hold (depcount + 1)
* Control Release (depcount - 1)
* Data Hold (refcount + 1)
* Data Release (refcount - 1)

#### Example for a matmul

We first create 2 DMA tasks (same Opcode, different Task ID) to load A and B and
set their allocated sizes.

In order to provide the source addresses, we create 2 DMA *descriptor* tasks. We
set the sizes of those to the DMA request descriptor size. These descriptors
will have depcount set to 1; once the SIMT cores fill in the descriptors to the
allocated space, depcount is decremented through a release instruction.

The DMA tasks depend on the descriptor tasks - which increases their refcounts
by 1. The DMA tasks each has depcount set to 1. Upon descriptor release, the DMA
tasks get issued.

At the same time, we start constructing the matmul descriptor. We create the
tasks, get the allocations, fill them in. We construct the matmul task with
Opcode set to executing the matmul, dependent on the descriptor and the two DMA
tasks. We release the descriptor.

Once both DMAs finish, matmul depcount reduces to 0 from an initial 3 and gets
issued. DMA task gets retired, refcounts decrease by 1 but still has 1 (the
matmul still depends on it) so memory is not yet released.

Once matmul finishes, refcount on the DMA allocations reduce to 0. If we want to
use the matmul's result, a manual data hold on the matmul task is required to
increment the refcount by 1.

### The Back End

Task issue ports contains the task instance ID, as well the dependency IDs. The
former can be used to access the allocated shared memory space. To send request
data, use a descriptor task dependency (see matmul example), the address to
which is in the dependency IDs.

Retirement mode like Neutrino V1 dictates when a task can be retired from the
scoreboard. V1 specified a vauge "accelerator" retire mode that has a hardware
wire retiring a task; this was never considered in detail. The new Neutrino
design specifies a writeback-like interface with multiple task completion ports.
Each port can dictate a completed task instance ID. The accelerator is
responsible for reading the descriptor, source data, and sending a response.

