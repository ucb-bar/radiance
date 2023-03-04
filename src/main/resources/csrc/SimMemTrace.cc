#ifndef NO_VPI
#include <vpi_user.h>
#include <svdpi.h>
#endif
#include <string>
#include <cstdio>
#include <cassert>
#include <unistd.h>
#include "SimMemTrace.h"

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

  while (infile >> line.cycle >> line.loadstore >> line.core_id >>
         line.thread_id >> std::hex >> line.address >> line.data >> std::dec >>
         line.data_size) {
    trace.push_back(line);
  }
  curr_line = trace.cbegin();

  printf("MemTraceReader: finished parsing\n");
}

MemTraceLine MemTraceReader::tick() {
  MemTraceLine line;

  if (finished()) {
    cycle++;
    return line;
  }

  line = *curr_line;
  assert(line.cycle >= cycle && "missed some trace lines past their cycles");
  while (line.cycle == cycle) {
    printf("cycle: %ld\n", cycle);
    line = *(++curr_line);
  }

  cycle++;
  return line;
}

extern "C" void memtrace_init(const char *filename) {
  printf("memtrace_init: filename=[%s]\n", filename);

  reader = std::make_unique<MemTraceReader>(filename);
  // parse file upfront
  reader->parse();
}

extern "C" void memtrace_tick(unsigned char *trace_read_valid,
                              unsigned char trace_read_ready,
                              unsigned long *trace_read_cycle,
                              unsigned long *trace_read_address,
                              unsigned char *trace_read_finished) {
  // printf("memtrace_tick()\n");
  if (!trace_read_ready) {
    return;
  }

  auto line = reader->tick();
  *trace_read_valid = line.valid;
  *trace_read_cycle = line.cycle;
  *trace_read_address = line.address;
  // This means finished and valid will go up at the same cycle.  Need to
  // handle this without skipping the last line.
  *trace_read_finished = reader->finished();

  return;
}
