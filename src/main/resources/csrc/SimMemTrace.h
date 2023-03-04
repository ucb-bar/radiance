#include <vector>
#include <memory>
#include <fstream>

class MemTraceReader;

// Global singleton instance of MemTraceReader
static std::unique_ptr<MemTraceReader> reader;

struct MemTraceLine {
  bool valid = false;
  long cycle = 0;
  char loadstore[10];
  int core_id = 0;
  int thread_id = 0;
  unsigned long address = 0;
  unsigned long data = 0;
  int data_size = 0;
};

class MemTraceReader {
public:
  MemTraceReader(const std::string &filename);
  ~MemTraceReader();
  void parse();
  MemTraceLine tick();
  bool finished() const { return curr_line == trace.cend(); }

  std::ifstream infile;
  std::vector<MemTraceLine> trace;
  std::vector<MemTraceLine>::const_iterator curr_line;
  long cycle = 0;
};

extern "C" void memtrace_init(const char *filename);
extern "C" void memtrace_tick(unsigned char *trace_read_valid,
                              unsigned char trace_read_ready,
                              unsigned long *trace_read_cycle,
                              unsigned long *trace_read_address,
                              unsigned char *trace_read_finished);
