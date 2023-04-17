#ifndef NO_VPI
#include <vpi_user.h>
#include <svdpi.h>
#endif
#include <string>
#include <string.h>
#include <cstdio>
#include <cmath>
#include <cassert>
#include <unistd.h>
#include "SimMemTrace.h"

// Global singleton instance
static std::unique_ptr<MemTraceReader> reader;

MemTraceReader::MemTraceReader(const std::string &filename) {
  char cwd[4096];
  if (getcwd(cwd, sizeof(cwd))) {
    printf("MemTraceReader: current working dir: %s\n", cwd);
  }

  infile.open(filename);
  if (infile.fail()) {
    fprintf(stderr, "failed to open file %s\n", filename.c_str());
  }
}

MemTraceReader::~MemTraceReader() {
  infile.close();
  printf("MemTraceReader destroyed\n");
}

// Parse trace file in its entirety and store it into internal structure.
// TODO: might block for a long time when the trace gets big, check if need to
// be broken down
void MemTraceReader::parse() {
  MemTraceLine line;

  printf("MemTraceReader: started parsing\n");

  long size = 0;
  std::string loadstore; // FIXME: likely slow
  while (infile >> line.cycle >> loadstore >> line.core_id >>
         line.lane_id >> std::hex >> line.address >> line.data >> std::dec >>
         size) {
    line.valid = true;

    line.is_store = (loadstore == "STORE");

    assert(size > 0 && "invalid size in trace");
    int lgsize = static_cast<int>(log2(size));
    assert((size & ~(~0lu << lgsize)) == 0 &&
           "non-power-of-2 size detected in trace");
    line.log_data_size = lgsize;

    trace.push_back(line);
  }
  read_pos = trace.cbegin();

  printf("MemTraceReader: finished parsing\n");
}

// Try to read a memory request that might have happened at a given cycle, on a
// given SIMD lane (= "thread").  In case no request happened at that point,
// return an empty line with .valid = false.
MemTraceLine MemTraceReader::read_trace_at(const long cycle,
                                           const int lane_id) {
  MemTraceLine line;
  line.valid = false;

  // printf("tick(): cycle=%ld\n", cycle);

  if (finished()) {
    return line;
  }

  line = *read_pos;
  // It should always be guaranteed that we consumed all of the past lines, and
  // the next line is in the future.
  if (line.cycle < cycle) {
    // fprintf(stderr, "line.cycle=%ld, cycle=%ld\n", line.cycle, cycle);
    assert(false && "some trace lines are left unread in the past");
  }

  if (line.lane_id != lane_id) {
    line.valid = false;
  }
  if (line.cycle > cycle) {
    // We haven't reached the cycle mark specified in this line yet, so we don't
    // read it right now.
    return MemTraceLine{};
  } else if (line.cycle == cycle && line.lane_id == lane_id) {
    printf("fire! cycle=%ld, valid=%d, %s addr=%lx, size=%d \n", cycle,
           line.valid, (line.is_store ? "STORE" : "LOAD"), line.address,
           line.log_data_size);

    // FIXME! Currently lane_id is assumed to be in round-robin order, e.g.
    // 0->1->2->3->0->..., both in the trace file and the order the caller calls
    // this function.  If this is not true, we cannot simply monotonically
    // increment read_pos.

    // Only advance pointer when cycle and threa_id both match
    // now increaseing sequence is fine (0, 1, 3), but unordered is not fine (0, 3, 1)
    ++read_pos;
  }

  return line;
}

extern "C" void memtrace_init(const char *filename) {
#ifndef NO_VPI
  s_vpi_vlog_info info;
  if (!vpi_get_vlog_info(&info)) {
    fprintf(stderr, "fatal: failed to get plusargs from VCS\n");
    exit(1);
  }
  const char* TRACEFILENAME_PLUSARG = "+memtracefile=";
  for (int i = 0; i < info.argc; i++) {
    char* input_arg = info.argv[i];
    if (strncmp(input_arg, TRACEFILENAME_PLUSARG,
                strlen(TRACEFILENAME_PLUSARG)) == 0) {
      filename = input_arg + strlen(TRACEFILENAME_PLUSARG);
      break;
    }
  }
#endif

  printf("memtrace_init: filename=[%s]\n", filename);

  reader = std::make_unique<MemTraceReader>(filename);
  // parse file upfront
  reader->parse();
}

// TODO: accept core_id as well
extern "C" void memtrace_query(unsigned char trace_read_ready,
                               unsigned long trace_read_cycle,
                               int           trace_read_lane_id,
                               unsigned char *trace_read_valid,
                               unsigned long *trace_read_address,
                               unsigned char *trace_read_is_store,
                               int           *trace_read_size,
                               unsigned long *trace_read_data,
                               unsigned char *trace_read_finished) {
  // printf("memtrace_query(cycle=%ld, tid=%d)\n", trace_read_cycle,
  //        trace_read_lane_id);

  if (!trace_read_ready) {
    return;
  }

  auto line = reader->read_trace_at(trace_read_cycle, trace_read_lane_id);
  *trace_read_valid = line.valid;
  *trace_read_address = line.address;
  *trace_read_is_store = line.is_store;
  *trace_read_size = line.log_data_size;
  *trace_read_data = line.data;
  // This means finished and valid will go up at the same cycle.  Need to
  // handle this without skipping the last line.
  *trace_read_finished = reader->finished();

  return;
}
