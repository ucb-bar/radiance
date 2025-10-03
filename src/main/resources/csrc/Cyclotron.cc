#ifndef NO_VPI
#include <svdpi.h>
#include <vpi_user.h>
#endif
#include <stdint.h>

extern "C" void cyclotron_init_rs(int num_lanes);
extern "C" void cyclotron_init(int num_lanes) { cyclotron_init_rs(num_lanes); }

extern "C" void cyclotron_tick_rs(const uint8_t *ibuf_ready,
                                  uint8_t *ibuf_valid, uint32_t *ibuf_pc,
                                  uint32_t *ibuf_op, uint32_t *ibuf_rd,
                                  uint8_t *finished);
extern "C" void cyclotron_tick(const uint8_t *ibuf_ready, uint8_t *ibuf_valid,
                               uint32_t *ibuf_pc, uint32_t *ibuf_op,
                               uint32_t *ibuf_rd, uint8_t *finished) {
  cyclotron_tick_rs(ibuf_ready, ibuf_valid, ibuf_pc, ibuf_op, ibuf_rd,
                    finished);
}
