# Programming Model

The SIMT program would resemble this:
Generated kernel header:
```c
typedef uint8_t run_id_t;
typedef uint8_t task_id_t;
task_id_t matmul_task = 1;
typedef struct { void *addr_a; void *addr_b; } matmul_args_t;
```

GPU kernel (suppose there's a pipeline preprocess -> matmul -> postprocess):
```c

run_t preprocess_done;
run_t matmul_done;

if (warp_should_preprocess) {
	while (still_have_stuff) {
		preproc_done = neutrino_invoke(0, NO_DEPS, ASYNC | CLUSTER | MANUAL);
		do_preprocess_stuff();
		// notify consumer
		neutrino_complete(preproc_done, PREPROCESS_WARPS);

		// kick off matmul asynchronously
		neutrino_payload((matmul_args_t) {addr_a, addr_b});
		matmul_done = neutrino_invoke(matmul_task, DEPS(preproc_done), ASYNC | SINGLE_THREAD | SIGNAL);
	}
} else if (warp_should_postprocess) {
	while (still_have_stuff) {
		// wait for matmul stage done
		neutrino_invoke(0, DEPS(matmul_done), SYNC | SOME_WARPS | IMMEDIATE);
		do_postprocess_stuff();
	}
}

int stage0;
int stage1;
int stage2;

if (preproc) {
	while (i) {
		sync();
		invoke(stage0(i));
		i++;
	}
} else {
	while (i) {
		sync();
		wait(stage0(i));
		i++;
	}
}

function barrier(warps) {
	neutrino_invoke(0, NO_DEPS, SYNC | warps | IMMEDIATE);
}

while (more_to_process) {
	neutrino_payload((matmul_args_t) {addr_a, addr_b});
	matmul_done = neutrino_invoke(matmul_task, NO_DEPS, ASYNC | SINGLE_THREAD | SIGNAL);

	softmax_done = neutrino_invoke(0, DEPS(prev_matmul_done), SYNC | ALL_WARPS | DUMMY | MANUAL);
	do_softmax();
	neutrino_complete(softmax_done, ALL_WARPS);

	prev_matmul_done = matmul_done;
}

// synchronize the whole cluster
neutrino_invoke(0, NO_DEPS, SYNC | CLUSTER | DUMMY | IMMEDIATE);
do_gpu_stuff();
```
In reality this is also way too verbose. There is probably a good way of generating all this boilerplate - good target for 265 project?

Neutrino's source:
```c
void matmul_task(void *addr_a, void* addr_b) {
	do_matmul();
	maybe_complete_the_matmul_run();
}
```
The Neutrino binary compiler, which runs first, should:
* Assign `matmul_task` a task ID;
* Identify the function arguments and make a struct;
* Generate a header file for SIMT so that it could reference both the task ID and the task argument struct;
* Attach boilerplate such as the interrupt handler;
* Generate a binary with the task functions.

The Muon compiler should:
* Automatically include the generated Neutrino header file;
* Assemble the invoke and complete instructions into single lines;
* Expand the payload calls into multiple instructions, as required by the payload size;
* Copy the Neutrino binary into a special section;
* Include ROM code that delivers the Neutrino binary.


## Pipelined Synchronization

A common use case for job orchestration is pipelined synchronization, where the
producer job and consumer job runs concurrently in a pipelined manner.  The
execution of the producer of the next data element is overlapped with the
execution of the consumer working on the previous data element.

In this case, you need two synchronization points:
* Consumer waits for the producer to "enqueue" the data.  This is the "true" data
  dependency that is intrinsic to the operation sequence.
* Producer waits for the consumer to "dequeue" the data.  This is a "false"
  dependency that exists because of the resource constraints of not being able
  to store an indefinite amount of intermediate data to the memory.

The `pipe.produce_wait()` and `pipe.consume_wait()` function handles the above
two synchronizations, respectively.

To achieve this synchronization, there exists a semaphore variable that
represents the current number of jobs enqueued to the pipeline.
The `pipe.produce_wait()` call will block until the semaphore value becomes
smaller than `num_stage - 1`, and `pipe.consume_wait()` until the value
is larger than `0`.
The actual increment and decrement of the semaphore will happen at
`pipe.produce_complete()` and `pipe.consume_complete()`, which represents
enqueueing and dequeueing a job to the pipeline, respectively.

In this way, you establish a critical region between `consume_wait()` and
`consume_complete()` calls that guarantees at least one producer job has fully
completed its operation and stored a valid result to the memory.  The same
holds for `produce_wait()` and `produce_complete()`.

This mechanism handles the synchronization aspect; in terms of how the data is
communicated between the producer and consumer jobs, see [Memory
Orchestration](#memory-orchestration).


## Memory Orchestration

Shared memory is used as the main data sharing mechanism across jobs.
Therefore, it makes sense to provide the programmer with high-level primitives
to memory objects, rather than have them do manual allocation and address
calculation.

Primitives that need to be supported:

* Memory allocation and de-allocation, i.e. `malloc()` and `free()`
  * Need to have some alignment requirement to ensure fast SRAM accesses
* FIFO queue primitives, i.e. `fifo.push()` and `fifo.pop()`
  * Use case: Double-buffered pipelined scheduling
  * Circular queue a good mapping for fixed-size SMEM usage
  * Can be in software, although some SIMT opportunity costs.
* Stacks, i.e. `stack.push()` and `stack.pop()`.
  * Use case: BVH traversal stack
  * Can be in software.
* What else?

Combining the pipelined sync + memory orchestration above, a double-buffered
software pipelining loop code might look like:

```cpp
// pipeline initialization
pipe = init_pipe(num_stages);
fifo = init_fifo(num_stages, elem_size);
pipe.invoke(PRODUCE, MATMUL, ...);
...

while (...) {
  // producer: wait for pipeline enqueue
  // this will register a SIMT job that waits for semaphore <= n-1
  pipe.produce_wait();
  producer_buf = fifo.push();
  // this will register a matmul job
  matmul_job = pipe.invoke(PRODUCE,
               MATMUL, producer_buf, ...);
  // this will associate the matmul job with produce completion signaling,
  // and mark completion to the SIMT producer job.
  // this operation will never block.
  pipe.produce_complete_on(matmul_job);

  // consumer: wait for pipeline dequeue
  // this will register a SIMT job that waits for semaphore > 0
  pipe.consume_wait();
  consumer_buf = fifo.pop();
  do_softmax(consumer_buf);
  // this will decrement the semaphore,
  // and mark completion to the SIMT consumer job.
  // this operation will never block.
  pipe.consume_complete();
}
```

Note on `pipe.produce_complete_on(job)`: This function should inform Neutrino
that the `job` should signal the pipeline's produce end on completion, in
addition to marking completion on the SIMT producer job that was registered at
`pipe.produce_wait()`.  This ensures that the matmul job's completion signals
the pipeline and not the SIMT's, which completes immediately after
asynchronously invoking matmul.

### Discussions

* Should the pipeline usage be kept track in hardware, or in software?
  * If in software, produce_wait() will block until a variable indicating
    queue usage goes below 2 (2 stages in double-buffer); consume_wait()
    will block until that number goes over 0.
  * There's no real way to implement this other than a spinloop however,
    because a thread cannot sleep itself; this might necessitate hardware
    support in Neutrino.
* What happens if a job is a consumer of one pipeline and a producer of
  another pipeline at the same time?


## Job ID Allocation

**Problem 1**: When do we de-allocate a job ID: completion or "used"?

If we mark a job as completed but hold on to the job ID without de-allocating
it, it eliminates any ambiguity to a job's state.  However, it creates pressure
for the structural resource necessary to store the jobs that are completed.
Moreover it becomes an issue to decide when it is safe to retire those jobs:
you can never know ahead-of-time when the job will never be referred again in
the future in a program.

Therefore we choose to de-allocate a job's ID as soon as it completes.
However, now an un-allocated ID can mean two things -- the job hasn't started
yet, or it has completed and retired from the scoreboard.

To work around this issue, we need to differentiate between the two state using
the _value_ of the ID.  A simple solution is to allocate IDs using an
ever-incrementing counter.  This implies that if a younger job has a higher ID
than that of another job that it depends on, but that ID is retired from
scoreboard, then we can guarantee that job has already run and finished.
Because the counter is in hardware and has limited bit-width, this creates a
wrap-around issue; for this, we need to reasonably assume the maximum possible
number of outstanding jobs, and use that to determine if an older-but-higher ID
falls within that range from the younger ID.

**Problem 2**: How does the programmer refer to another job to synchronize to
it, especially when the job is running in a different context (e.g. thread,
warp, threadblock)?  The current context would have no knowledge on the ID of
this job because it did not participate in the allocation process in any way.

The simplest solution is to let the programmer allocate the ID completely
statically and manually.  The ID will contain bit fields that indicate
(1) the unique ID given to that job, and (2) the ID of the "context" that
that job was created in, and (3) the monotonically-increasing counter that
represents causality.  While (3) will be abstracted away in hardware,
(1) and (2) must be specified manually via the API.

An example use case is in ReSTIR GI, where the spatial re-sampling stage of the
current thread has to wait until the neighboring pixel thread's initial
sampling stage finishes.  That code might look like:

```cpp
// Initial sampling stage
initial_job = job_invoke(SIMT, INIT_JOB_ID, tid);
...
initial_job.complete();

// Spatial resampling stage
let neighbors = get_neighbors();
for neighbor_id in neighbors {
  job_wait(INIT_JOB_ID, neighbor_id);
  
  // access neighbor’s sample data in SMEM
  rad = smem_reservoirs[neighbor_id];
  ...
}
```

Although it would be great to have `initial_job = job_invoke(...);` bind an ID
that is unique within a context but consistent across contexts so that you
could later simply call `initial_job.wait(neighbor_id)`, that prohibits the
`job_invoke()` function to be called within a data-dependent control branch,
which is overly strict.
