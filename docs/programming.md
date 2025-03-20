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
  // mark completion to the SIMT producer job,
  // and asynchronously retire from SIMT
  pipe.produce_complete_on(matmul_job);

  // consumer: wait for pipeline dequeue
  // this will register a SIMT job that waits for semaphore > 0
  pipe.consume_wait();
  consumer_buf = fifo.pop();
  do_softmax(consumer_buf);
  // this will decrement the semaphore,
  // mark completion to the SIMT consumer job,
  // and asynchronously retire from SIMT
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

```cpp
// GEMM Q*KT
gemmQK_job = job_invoke(MATMUL, dim,
              smem_Q, smem_K, smem_QK);

// Softmax
int warps[] = {0,1,2,3};
simt_job = gemmQK_job.wait(warps);
do_softmax(smem_QK, smem_P);
simt_job.complete();

// GEMM P*V
gemmPV_job = job_invoke(MATMUL, dim,
              smem_P, smem_V, …);
```

How does the programmer refer to another job in the code to do synchronization
with it?

- **Within a context**: If referring to jobs in the same thread, this can be done
  by letting the management unit do allocation, and return the ID to the thread
  context. Then refer back to the ID that’s stored in the register file (local
  variable).
  - This works across loop iterations, as long as you store the IDs across
    iterations in e.g. an array.
  - Can also be done statically, e.g. have a bit field in the ID be manually
    specified in the program, which can simply be the loop iteration.
- **Across contexts**: How does the programmer specify the job scheduled in a
  different context (i.e. not at the same thread)? If we do dynamic allocation
  of job ID independently across contexts, you cannot refer to jobs across
  contexts easily.
  - A static mapping scheme, where e.g. each thread gets mapped a static
    range of job IDs, i.e. a bit field in the ID indicates the thread number.
  - Programmer still needs to know how jobs are assigned within each thread,
    e.g. what’s the ray tracing job in another thread
      - Can be communicated via shared memory?
      - Or, let the programmer again statically decide allocation scheme:
        E.g. for each thread, ID offset 1 is matmul, offset 2 is RT. Most
        flexible, simple, but can be burdensome.


