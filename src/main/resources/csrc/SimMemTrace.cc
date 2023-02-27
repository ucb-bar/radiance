#include <vpi_user.h>
#include <svdpi.h>
#include <memory>
#include <string>
#include <fstream>
#include <cstdio>
#include <unistd.h>

class MemTraceReader;
static std::unique_ptr<MemTraceReader> reader;

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
  bool tick();

  std::ifstream infile;
};

// Returns true if there is no more memory trace left to read.
bool MemTraceReader::tick() {
  std::string line;
  long cycle = 0;
  char loadstore[10]{0};
  int core_id = 0;
  int thread_id = 0;
  unsigned long address = 0;
  unsigned long data = 0;
  int data_size = 0;

  if (!(infile >> cycle >> loadstore >> core_id >> thread_id
        >> std::hex >>
        address >> data >> std::dec >> data_size
        )) {
    return true;
  }

  printf("cycle: %ld\n", cycle);
  return false;
}

extern "C" void memtrace_init(const char *filename) {
  reader = std::make_unique<MemTraceReader>(filename);
  printf("memtrace_init: filename=[%s]\n", filename);
}

extern "C" void memtrace_tick(unsigned char *trace_read_valid,
                              unsigned char trace_read_ready,
                              char *trace_read_bits) {
  *trace_read_bits = 42;

  *trace_read_valid = 0;
  if (reader->tick()) {
    *trace_read_valid = 1;
  }

  return;
}
