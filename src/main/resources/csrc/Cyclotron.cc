#ifndef NO_VPI
#include <svdpi.h>
#include <vpi_user.h>
#endif
#include <stdint.h>

extern "C" void cyclotron_init_rs(int num_lanes);
extern "C" void cyclotron_init(int num_lanes) { cyclotron_init_rs(num_lanes); }

extern "C" void cyclotron_get_trace_rs(const uint8_t *ready,
                                    uint8_t *valid, uint32_t *pc,
                                    uint32_t *op, uint32_t *rd,
                                    uint8_t *finished);
extern "C" void cyclotron_get_trace(const uint8_t *ready, uint8_t *valid,
                                 uint32_t *pc, uint32_t *op,
                                 uint32_t *rd, uint8_t *finished) {
  cyclotron_get_trace_rs(ready, valid, pc, op, rd, finished);
}
