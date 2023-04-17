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
#include "SimMemTrace.h"

// Global singleton instance
static std::unique_ptr<MemTraceWriter> logger;

MemTraceWriter::MemTraceWriter(const std::string &filename) {
  char cwd[4096];
  if (getcwd(cwd, sizeof(cwd))) {
    printf("MemTraceLogger: current working dir: %s\n", cwd);
  }

  outfile = fopen(filename.c_str(), "w");
  if (!outfile) {
    fprintf(stderr, "failed to open file %s\n", filename.c_str());
  }
}

MemTraceWriter::~MemTraceWriter() {
  fclose(outfile);
  printf("MemTraceWriter destroyed\n");
}

void MemTraceWriter::write_trace_at(const MemTraceLine line) {
  printf("tick(): cycle=%ld\n", line.cycle);

  fprintf(outfile, "%ld %s %d %d 0x%lx 0x%lx %u\n", line.cycle,
          (line.is_store ? "STORE" : "LOAD"), line.core_id, line.lane_id,
          line.address, line.data, (1u << line.log_data_size));
}

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

  logger = std::make_unique<MemTraceWriter>(filename);
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

  if (!trace_log_valid) {
    return;
  }

  printf("%s: [%lu] valid: address=%lx, tid=%u, size=%d\n", __func__,
         trace_log_cycle, trace_log_address, trace_log_lane_id,
         trace_log_size);

  MemTraceLine line{.valid = (trace_log_valid == 1),
                    .cycle = static_cast<long>(trace_log_cycle),
                    .is_store = (trace_log_is_store == 1),
                    .core_id = 0, // TODO support multicores
                    .lane_id = trace_log_lane_id,
                    .address = trace_log_address,
                    .data = trace_log_data,
                    .log_data_size = trace_log_size};

  logger->write_trace_at(line);
}
