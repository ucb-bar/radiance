#ifndef NO_VPI
#include <vpi_user.h>
#include <svdpi.h>
#endif
#include <string>
#include <cstring>
#include <cstdio>
#include <cassert>
#include <memory>
#include <unistd.h>
#include "SimMemTraceLogger.h"

// Global singleton instance
static std::unique_ptr<MemTraceLogger> logger;

MemTraceLogger::MemTraceLogger(const std::string &filename) {
  char cwd[4096];
  if (getcwd(cwd, sizeof(cwd))) {
    printf("MemTraceLogger: current working dir: %s\n", cwd);
  }

  infile.open(filename);
  if (infile.fail()) {
    fprintf(stderr, "failed to open file %s\n", filename.c_str());
  }
}

MemTraceLogger::~MemTraceLogger() {
  infile.close();
  printf("MemTraceLogger destroyed\n");
}

#if 0
// Parse trace file in its entirety and store it into internal structure.
// TODO: might block for a long time when the trace gets big, check if need to
// be broken down
void MemTraceLogger::parse() {
  MemTraceLine line;

  printf("MemTraceLogger: started parsing\n");

  while (infile >> line.cycle >> line.loadstore >> line.core_id >>
         line.lane_id >> std::hex >> line.address >> line.data >> std::dec >>
         line.data_size) {
    line.valid = true;
    trace.push_back(line);
  }
  read_pos = trace.cbegin();

  printf("MemTraceLogger: finished parsing\n");
}

// Try to read a memory request that might have happened at a given cycle, on a
// given SIMD lane (= "thread").  In case no request happened at that point,
// return an empty line with .valid = false.
MemTraceLine MemTraceLogger::read_trace_at(const long cycle,
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
    printf("fire! cycle=%ld, valid=%d, %s addr=%x \n", cycle, line.valid,
           line.loadstore, line.address);

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
#endif

extern "C" void memtracelogger_init(const char *filename) {
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

  logger = std::make_unique<MemTraceLogger>(filename);
}

// TODO: accept core_id as well
extern "C" void memtracelogger_log(unsigned char trace_log_valid,
                                   unsigned long trace_log_cycle,
                                   unsigned long trace_log_address,
                                   int           trace_log_lane_id,
                                   unsigned char trace_log_is_store,
                                   int           trace_log_size,
                                   unsigned long trace_log_data,
                                   unsigned char *trace_log_ready) {
  // printf("memtrace_query(cycle=%ld, tid=%d)\n", trace_read_cycle,
  //        trace_read_lane_id);
  *trace_log_ready = 1;

  if (trace_log_valid) {
    printf("%s: [%lu] valid: address=%lx, tid=%u, size=%d\n", __func__,
           trace_log_cycle, trace_log_address, trace_log_lane_id,
           trace_log_size);
  }
}
