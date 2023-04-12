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
  int lane_id = 0;
  unsigned long address = 0;
  unsigned long data = 0;
  int data_size = 0;
};

class MemTraceReader {
public:
  MemTraceReader(const std::string &filename);
  ~MemTraceReader();
  void parse();
  MemTraceLine read_trace_at(const long cycle, const int lane_id);
  bool finished() const { return read_pos == trace.cend(); }

  std::ifstream infile;
  std::vector<MemTraceLine> trace;
  std::vector<MemTraceLine>::const_iterator read_pos;
};

extern "C" void memtrace_init(const char *filename);
extern "C" void memtrace_query(unsigned char trace_read_ready,
                               unsigned long trace_read_cycle,
                               int           trace_read_lane_id,
                               unsigned char *trace_read_valid,
                               unsigned long *trace_read_address,
                               unsigned char *trace_read_is_store,
                               int           *trace_read_size,
                               unsigned long *trace_read_data,
                               unsigned char *trace_read_finished);
