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

```
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

## Pipelined synchronization

A common use case for job orchestration is pipelined synchronization, where the
producer job and consumer job runs concurrently in a pipelined manner.  The
execution of the producer of the next data element is overlapped with the
execution of the consumer working on the previous data element.

In this case, you need two synchronization points:
* Consumer waits for the producer to "enqueue" the data.  This is the "true" data
  dependency that is intrinsic to the operation sequence.
* Producer waits for the consumer to "dequeue" the data.  This is an "anti"
  dependency that exists because of the resource constraints in storing
  intermediate data to memory, e.g. waiting for a free space in the
  double-buffer.

The `pipe.produce_wait()` and `pipe.consume_wait()` function handles the above
two synchronizations, respectively.

```
// pipeline initialization
pipe = init_pipe();
pipe.invoke(PRODUCE, MATMUL, ...);
...

while (...) {
  // producer: wait for buffer use
  pipe.produce_wait();
  producer_buf = pipe.produce_buf();
  matmul_job = pipe.invoke(PRODUCE,
               MATMUL, producer_buf, ...);

  // consumer: wait for buffer fill
  pipe.consume_wait();
  consumer_buf = pipe.consume_buf();
  do_softmax(consumer_buf);


  pipe.complete(CONSUME);
}
```

### Discussions

[] Should the pipeline usage be kept track in hardware, or in software?
   * If in software, produce_wait() will block until a variable indicating
     queue usage goes below 2 (2 stages in double-buffer); consume_wait()
     will block until that number goes over 0.
   * There's no real way to implement this other than a spinloop however,
     because a thread cannot sleep itself; this might necessitate hardware
     support in Neutrino.
[] What happens if a job is a consumer of one pipeline and a producer of
   another pipeline at the same time? 
