#include <vpi_user.h>
#include <svdpi.h>
#include <memory>
#include <string>
#include <fstream>
#include <cstdio>
#include <unistd.h>

class MemTraceReader;

// Global singleton instance of MemTraceReader
static std::unique_ptr<MemTraceReader> reader;

struct MemTraceLine {
  bool valid = false;
  unsigned long cycle = 0;
  char loadstore[10];
  int core_id = 0;
  int thread_id = 0;
  unsigned long address = 0;
  unsigned long data = 0;
  int data_size = 0;
};

class MemTraceReader {
public:
  MemTraceReader(const std::string &filename) {
    char cwd[4096];
    if (getcwd(cwd, sizeof(cwd))) {
        printf("MemTraceReader: current working dir: %s\n", cwd);
    }

    infile.open(filename);
    if (infile.fail()) {
        fprintf(stderr, "failed to open file %s\n", filename);
    }
  }
  ~MemTraceReader() {
    infile.close();
    printf("MemTraceReader destroyed\n");
  }
  MemTraceLine tick();

  std::ifstream infile;
};

MemTraceLine MemTraceReader::tick() {
  MemTraceLine line;

  line.valid = false;
  if (infile >> line.cycle >> line.loadstore >> line.core_id >>
      line.thread_id >> std::hex >> line.address >> line.data >> std::dec >>
      line.data_size) {
    line.valid = true;
    printf("cycle: %ld\n", line.cycle);
  }

  return line;
}

extern "C" void memtrace_init(const char *filename) {
  reader = std::make_unique<MemTraceReader>(filename);
  printf("memtrace_init: filename=[%s]\n", filename);
}

extern "C" void memtrace_tick(unsigned char *trace_read_valid,
                              unsigned char trace_read_ready,
                              unsigned long *trace_read_cycle,
                              unsigned long *trace_read_address) {
  auto line = reader->tick();

  *trace_read_valid = line.valid;
  *trace_read_cycle = line.cycle;
  *trace_read_address = line.address;

  return;
}
