#ifndef NO_VPI
#include <svdpi.h>
#include <vpi_user.h>
#endif
#include <stdint.h>
#include <string>

extern "C" {

const char *vpi_get_binary() {
#ifndef NO_VPI
  static std::string elf_arg;
  s_vpi_vlog_info info;
  if (!vpi_get_vlog_info(&info)) {
    elf_arg.clear();
    return elf_arg.c_str();
  }
  for (int i = 1; i < info.argc; i++) {
    const char *arg = info.argv[i];
    if (arg == nullptr)
      continue;
    if (arg[0] == '+' || arg[0] == '-')
      continue;
    elf_arg = arg;
    return elf_arg.c_str();
  }
  elf_arg.clear();
  return elf_arg.c_str();
#else
  return "";
#endif
}

const char *vpi_get_trace_db_path() {
#ifndef NO_VPI
  static std::string trace_db_path;
  s_vpi_vlog_info info;
  if (!vpi_get_vlog_info(&info)) {
    trace_db_path.clear();
    return trace_db_path.c_str();
  }
  for (int i = 1; i < info.argc; i++) {
    const char *arg = info.argv[i];
    if (arg == nullptr)
      continue;
    constexpr const char *kPrefix = "+trace-db=";
    if (std::string(arg).rfind(kPrefix, 0) == 0) {
      trace_db_path = arg + std::char_traits<char>::length(kPrefix);
      return trace_db_path.c_str();
    }
  }
  trace_db_path.clear();
  return trace_db_path.c_str();
#else
  return "";
#endif
}

void cyclotron_init_rs(const char* elfname, const char* trace_db_path);
void cyclotron_init(const char* elfname, const char* trace_db_path) {
  cyclotron_init_rs(elfname, trace_db_path);
}

void cyclotron_fetch_rs(
    uint8_t  req_valid,
    uint64_t req_bits_tag,
    uint32_t req_bits_pc,
    uint8_t* resp_valid_ptr,
    uint64_t* resp_bits_tag_ptr,
    uint64_t* resp_bits_inst_ptr
);

void cyclotron_fetch(
    uint8_t  req_valid,
    uint64_t req_bits_tag,
    uint32_t req_bits_pc,
    uint8_t* resp_valid_ptr,
    uint64_t* resp_bits_tag_ptr,
    uint64_t* resp_bits_inst_ptr
) {
    cyclotron_fetch_rs(
        req_valid,
        req_bits_tag,
        req_bits_pc,
        resp_valid_ptr,
        resp_bits_tag_ptr,
        resp_bits_inst_ptr
    );
}

void cyclotron_gmem_rs(
    const uint8_t* req_valid,
    uint8_t* req_ready,
    const uint8_t* req_bits_store,
    const uint32_t* req_bits_address,
    const uint8_t* req_bits_size,
    const uint32_t* req_bits_tag,
    const uint32_t* req_bits_data,
    const uint8_t* req_bits_mask,
    const uint8_t* resp_ready,
    uint8_t* resp_valid,
    uint32_t* resp_bits_tag,
    uint32_t* resp_bits_data
);

void cyclotron_gmem(
    const uint8_t* req_valid,
    uint8_t* req_ready,
    const uint8_t* req_bits_store,
    const uint32_t* req_bits_address,
    const uint8_t* req_bits_size,
    const uint32_t* req_bits_tag,
    const uint32_t* req_bits_data,
    const uint8_t* req_bits_mask,
    const uint8_t* resp_ready,
    uint8_t* resp_valid,
    uint32_t* resp_bits_tag,
    uint32_t* resp_bits_data
) {
    cyclotron_gmem_rs(
        req_valid,
        req_ready,
        req_bits_store,
        req_bits_address,
        req_bits_size,
        req_bits_tag,
        req_bits_data,
        req_bits_mask,
        resp_ready,
        resp_valid,
        resp_bits_tag,
        resp_bits_data
    );
}

void cyclotron_frontend_rs(
    const uint8_t* ibuf_ready_vec,
    uint8_t*       ibuf_valid_vec,
    uint32_t*      ibuf_pc_vec,
    uint8_t*       ibuf_wid_vec,
    uint8_t*       ibuf_op_vec,
    uint8_t*       ibuf_opext_vec,
    uint8_t*       ibuf_f3_vec,
    uint8_t*       ibuf_rd_addr_vec,
    uint8_t*       ibuf_rs1_addr_vec,
    uint8_t*       ibuf_rs2_addr_vec,
    uint8_t*       ibuf_rs3_addr_vec,
    uint8_t*       ibuf_f7_vec,
    uint32_t*      ibuf_imm32_vec,
    uint32_t*      ibuf_imm24_vec,
    uint8_t*       ibuf_csr_imm_vec,
    uint32_t*      ibuf_tmask_vec,
    uint64_t*      ibuf_raw_vec,
    uint8_t*       finished_ptr
);

void cyclotron_frontend(
    const uint8_t* ibuf_ready_vec,
    uint8_t*       ibuf_valid_vec,
    uint32_t*      ibuf_pc_vec,
    uint8_t*       ibuf_wid_vec,
    uint8_t*       ibuf_op_vec,
    uint8_t*       ibuf_opext_vec,
    uint8_t*       ibuf_f3_vec,
    uint8_t*       ibuf_rd_addr_vec,
    uint8_t*       ibuf_rs1_addr_vec,
    uint8_t*       ibuf_rs2_addr_vec,
    uint8_t*       ibuf_rs3_addr_vec,
    uint8_t*       ibuf_f7_vec,
    uint32_t*      ibuf_imm32_vec,
    uint32_t*      ibuf_imm24_vec,
    uint8_t*       ibuf_csr_imm_vec,
    uint32_t*      ibuf_tmask_vec,
    uint64_t*      ibuf_raw_vec,
    uint8_t*       finished_ptr
) {
    cyclotron_frontend_rs(
        ibuf_ready_vec,
        ibuf_valid_vec,
        ibuf_pc_vec,
        ibuf_wid_vec,
        ibuf_op_vec,
        ibuf_opext_vec,
        ibuf_f3_vec,
        ibuf_rd_addr_vec,
        ibuf_rs1_addr_vec,
        ibuf_rs2_addr_vec,
        ibuf_rs3_addr_vec,
        ibuf_f7_vec,
        ibuf_imm32_vec,
        ibuf_imm24_vec,
        ibuf_csr_imm_vec,
        ibuf_tmask_vec,
        ibuf_raw_vec,
        finished_ptr
    );
}

void cyclotron_tile_tick_rs(
    uint8_t*  imem_req_valid_ptr,
    uint8_t   imem_req_ready,
    uint32_t* imem_req_bits_address_ptr,
    uint64_t* imem_req_bits_tag_ptr,
    uint8_t*  imem_resp_ready_ptr,
    uint8_t   imem_resp_valid,
    uint64_t  imem_resp_bits_tag,
    uint64_t  imem_resp_bits_data,
    uint8_t*  dmem_req_valid_vec,
    const uint8_t* dmem_req_ready_vec,
    uint8_t*  dmem_req_bits_store_vec,
    uint32_t* dmem_req_bits_address_vec,
    uint8_t*  dmem_req_bits_size_vec,
    uint32_t* dmem_req_bits_tag_vec,
    uint32_t* dmem_req_bits_data_vec,
    uint8_t*  dmem_req_bits_mask_vec,
    uint8_t*  dmem_resp_ready_vec,
    const uint8_t* dmem_resp_valid_vec,
    const uint32_t* dmem_resp_bits_tag_vec,
    const uint32_t* dmem_resp_bits_data_vec,
    uint8_t*  finished_ptr
);

void cyclotron_tile_tick(
    uint8_t*  imem_req_valid_ptr,
    uint8_t   imem_req_ready,
    uint32_t* imem_req_bits_address_ptr,
    uint64_t* imem_req_bits_tag_ptr,
    uint8_t*  imem_resp_ready_ptr,
    uint8_t   imem_resp_valid,
    uint64_t  imem_resp_bits_tag,
    uint64_t  imem_resp_bits_data,
    uint8_t*  dmem_req_valid_vec,
    const uint8_t* dmem_req_ready_vec,
    uint8_t*  dmem_req_bits_store_vec,
    uint32_t* dmem_req_bits_address_vec,
    uint8_t*  dmem_req_bits_size_vec,
    uint32_t* dmem_req_bits_tag_vec,
    uint32_t* dmem_req_bits_data_vec,
    uint8_t*  dmem_req_bits_mask_vec,
    uint8_t*  dmem_resp_ready_vec,
    const uint8_t* dmem_resp_valid_vec,
    const uint32_t* dmem_resp_bits_tag_vec,
    const uint32_t* dmem_resp_bits_data_vec,
    uint8_t*  finished_ptr
) {
    cyclotron_tile_tick_rs(
        imem_req_valid_ptr,
        imem_req_ready,
        imem_req_bits_address_ptr,
        imem_req_bits_tag_ptr,
        imem_resp_ready_ptr,
        imem_resp_valid,
        imem_resp_bits_tag,
        imem_resp_bits_data,
        dmem_req_valid_vec,
        dmem_req_ready_vec,
        dmem_req_bits_store_vec,
        dmem_req_bits_address_vec,
        dmem_req_bits_size_vec,
        dmem_req_bits_tag_vec,
        dmem_req_bits_data_vec,
        dmem_req_bits_mask_vec,
        dmem_resp_ready_vec,
        dmem_resp_valid_vec,
        dmem_resp_bits_tag_vec,
        dmem_resp_bits_data_vec,
        finished_ptr
    );
}

void cyclotron_backend_rs(
    uint8_t issue_valid,
    uint8_t issue_warp_id,
    uint32_t issue_pc,
    uint8_t issue_op,
    uint8_t issue_opext,
    uint8_t issue_f3,
    uint8_t issue_rd_addr,
    uint8_t issue_rs1_addr,
    uint8_t issue_rs2_addr,
    uint8_t issue_rs3_addr,
    const uint32_t* issue_rs1_data_ptr,
    const uint32_t* issue_rs2_data_ptr,
    const uint32_t* issue_rs3_data_ptr,
    uint8_t issue_f7,
    uint32_t issue_imm32,
    uint32_t issue_imm24,
    uint8_t issue_csr_imm,
    const uint32_t* issue_pred_ptr,
    uint32_t issue_tmask,
    uint64_t issue_raw_inst,
    uint8_t* writeback_valid_ptr,
    uint32_t* writeback_pc_ptr,
    uint32_t* writeback_tmask_ptr,
    uint8_t* writeback_wid_ptr,
    uint8_t* writeback_rd_addr_ptr,
    uint32_t* writeback_rd_data_ptr,
    uint8_t* writeback_set_pc_valid_ptr,
    uint32_t* writeback_set_pc_ptr,
    uint8_t* writeback_set_tmask_valid_ptr,
    uint32_t* writeback_set_tmask_ptr,
    uint8_t* writeback_wspawn_valid_ptr,
    uint32_t* writeback_wspawn_count_ptr,
    uint32_t* writeback_wspawn_pc_ptr,
    uint8_t* writeback_ipdom_valid_ptr,
    uint32_t* writeback_ipdom_restored_mask_ptr,
    uint32_t* writeback_ipdom_else_mask_ptr,
    uint32_t* writeback_ipdom_else_pc_ptr,
    uint8_t* finished_ptr
);

void cyclotron_backend(
    uint8_t issue_valid,
    uint8_t issue_warp_id,
    uint32_t issue_pc,
    uint8_t issue_op,
    uint8_t issue_opext,
    uint8_t issue_f3,
    uint8_t issue_rd_addr,
    uint8_t issue_rs1_addr,
    uint8_t issue_rs2_addr,
    uint8_t issue_rs3_addr,
    uint32_t issue_rs1_data,
    uint32_t issue_rs2_data,
    uint32_t issue_rs3_data,
    uint8_t issue_f7,
    uint32_t issue_imm32,
    uint32_t issue_imm24,
    uint8_t issue_csr_imm,
    uint32_t issue_pred,
    uint32_t issue_tmask,
    uint64_t issue_raw_inst,
    uint8_t* writeback_valid,
    uint32_t* writeback_pc,
    uint32_t* writeback_tmask,
    uint8_t* writeback_wid,
    uint8_t* writeback_rd_addr,
    uint32_t* writeback_rd_data,
    uint8_t* writeback_set_pc_valid,
    uint32_t* writeback_set_pc,
    uint8_t* writeback_set_tmask_valid,
    uint32_t* writeback_set_tmask,
    uint8_t* writeback_wspawn_valid,
    uint32_t* writeback_wspawn_count,
    uint32_t* writeback_wspawn_pc,
    uint8_t* writeback_ipdom_valid,
    uint32_t* writeback_ipdom_restored_mask,
    uint32_t* writeback_ipdom_else_mask,
    uint32_t* writeback_ipdom_else_pc,
    uint8_t* finished
) {
    cyclotron_backend_rs(
        issue_valid,
        issue_warp_id,
        issue_pc,
        issue_op,
        issue_opext,
        issue_f3,
        issue_rd_addr,
        issue_rs1_addr,
        issue_rs2_addr,
        issue_rs3_addr,
        nullptr, // &issue_rs1_data,
        nullptr, // &issue_rs2_data,
        nullptr, // &issue_rs3_data,
        issue_f7,
        issue_imm32,
        issue_imm24,
        issue_csr_imm,
        nullptr, // &issue_pred,
        issue_tmask,
        issue_raw_inst,
        writeback_valid,
        writeback_pc,
        writeback_tmask,
        writeback_wid,
        writeback_rd_addr,
        writeback_rd_data,
        writeback_set_pc_valid,
        writeback_set_pc,
        writeback_set_tmask_valid,
        writeback_set_tmask,
        writeback_wspawn_valid,
        writeback_wspawn_count,
        writeback_wspawn_pc,
        writeback_ipdom_valid,
        writeback_ipdom_restored_mask,
        writeback_ipdom_else_mask,
        writeback_ipdom_else_pc,
        finished
    );
}

void cyclotron_imem_rs(
    uint8_t* imem_req_ready_ptr,
    const uint8_t imem_req_valid,
    const uint8_t imem_req_bits_store,
    const uint32_t imem_req_bits_address,
    const uint8_t imem_req_bits_size,
    const uint8_t imem_req_bits_tag,
    const uint64_t imem_req_bits_data,
    const uint8_t imem_req_bits_mask,
    const uint8_t imem_resp_ready,
    uint8_t* imem_resp_valid_ptr,
    uint8_t* imem_resp_bits_tag_ptr,
    uint64_t* imem_resp_bits_data_ptr
);

void cyclotron_imem(
    uint8_t* imem_req_ready,
    const uint8_t imem_req_valid,
    const uint8_t imem_req_bits_store,
    const uint32_t imem_req_bits_address,
    const uint8_t imem_req_bits_size,
    const uint8_t imem_req_bits_tag,
    const uint64_t imem_req_bits_data,
    const uint8_t imem_req_bits_mask,
    const uint8_t imem_resp_ready,
    uint8_t* imem_resp_valid,
    uint8_t* imem_resp_bits_tag,
    uint64_t* imem_resp_bits_data
) {
    cyclotron_imem_rs(
        imem_req_ready,
        imem_req_valid,
        imem_req_bits_store,
        imem_req_bits_address,
        imem_req_bits_size,
        imem_req_bits_tag,
        imem_req_bits_data,
        imem_req_bits_mask,
        imem_resp_ready,
        imem_resp_valid,
        imem_resp_bits_tag,
        imem_resp_bits_data
    );
}

void *cyclotron_mem_init_rs(uint64_t num_lanes);

void *cyclotron_mem_init(uint64_t num_lanes) {
    return cyclotron_mem_init_rs(num_lanes);
}

void cyclotron_mem_free_rs(void *mem_model_ptr);

void cyclotron_mem_free(void *mem_model_ptr) {
    cyclotron_mem_free_rs(mem_model_ptr);
}

void cyclotron_mem_rs(
    void *mem_model_ptr,
    
    uint8_t *req_ready,
    uint8_t req_valid,

    uint8_t req_store,
    uint32_t req_tag,
    const uint32_t *req_address,
    const uint32_t *req_data,
    const uint8_t  *req_mask,

    uint8_t resp_ready,
    uint8_t *resp_valid,

    uint32_t *resp_tag,
    uint32_t *resp_data,
    uint8_t  *resp_valids
);

// Note: VCS uses contiguous bits in memory to represent packed bit arrays, 
// this may not be the case on other simulators. 
void cyclotron_mem(
    void *mem_model_ptr,
    
    uint8_t *req_ready,
    uint8_t req_valid,

    uint8_t req_store,
    uint32_t req_tag,
    const uint32_t *req_address,
    const uint32_t *req_data,
    const uint8_t  *req_mask,

    uint8_t resp_ready,
    uint8_t *resp_valid,

    uint32_t *resp_tag,
    uint32_t *resp_data,
    uint8_t  *resp_valids
) {
    cyclotron_mem_rs(
        mem_model_ptr,
        req_ready,
        req_valid,
        req_store,
        req_tag,
        req_address,
        req_data,
        req_mask,
        resp_ready,
        resp_valid,
        resp_tag,
        resp_data,
        resp_valids
    );
};

void cyclotron_trace_rs(
    const uint32_t  cluster_id,
    const uint32_t  core_id,
    const uint64_t  cycle,
    const uint8_t   trace_valid,
    const uint32_t  trace_pc,
    const uint32_t  trace_warpId,
    const uint32_t  trace_tmask,
    const uint8_t   trace_regs_0_enable,
    const uint32_t  trace_regs_0_address,
    const uint32_t *trace_regs_0_data,
    const uint8_t   trace_regs_1_enable,
    const uint32_t  trace_regs_1_address,
    const uint32_t *trace_regs_1_data,
    const uint8_t   trace_regs_2_enable,
    const uint32_t  trace_regs_2_address,
    const uint32_t *trace_regs_2_data,
    const uint8_t  *dmem_req_valid,
    const uint8_t  *dmem_req_bits_store,
    const uint32_t *dmem_req_bits_address,
    const uint8_t  *dmem_req_bits_size,
    const uint32_t *dmem_req_bits_tag,
    const uint32_t *dmem_req_bits_data,
    const uint8_t  *dmem_req_bits_mask,
    const uint8_t  *dmem_resp_valid,
    const uint32_t *dmem_resp_bits_tag,
    const uint32_t *dmem_resp_bits_data,
    const uint8_t  *smem_req_valid,
    const uint8_t  *smem_req_bits_store,
    const uint32_t *smem_req_bits_address,
    const uint8_t  *smem_req_bits_size,
    const uint32_t *smem_req_bits_tag,
    const uint32_t *smem_req_bits_data,
    const uint8_t  *smem_req_bits_mask,
    const uint8_t  *smem_resp_valid,
    const uint32_t *smem_resp_bits_tag,
    const uint32_t *smem_resp_bits_data
);

void cyclotron_trace(
    const uint32_t  cluster_id,
    const uint32_t  core_id,
    const uint64_t  cycle,
    const uint8_t   trace_valid,
    const uint32_t  trace_pc,
    const uint32_t  trace_warpId,
    const uint32_t  trace_tmask,
    const uint8_t   trace_regs_0_enable,
    const uint32_t  trace_regs_0_address,
    const uint32_t *trace_regs_0_data,
    const uint8_t   trace_regs_1_enable,
    const uint32_t  trace_regs_1_address,
    const uint32_t *trace_regs_1_data,
    const uint8_t   trace_regs_2_enable,
    const uint32_t  trace_regs_2_address,
    const uint32_t *trace_regs_2_data,
    const uint8_t  *dmem_req_valid,
    const uint8_t  *dmem_req_bits_store,
    const uint32_t *dmem_req_bits_address,
    const uint8_t  *dmem_req_bits_size,
    const uint32_t *dmem_req_bits_tag,
    const uint32_t *dmem_req_bits_data,
    const uint8_t  *dmem_req_bits_mask,
    const uint8_t  *dmem_resp_valid,
    const uint32_t *dmem_resp_bits_tag,
    const uint32_t *dmem_resp_bits_data,
    const uint8_t  *smem_req_valid,
    const uint8_t  *smem_req_bits_store,
    const uint32_t *smem_req_bits_address,
    const uint8_t  *smem_req_bits_size,
    const uint32_t *smem_req_bits_tag,
    const uint32_t *smem_req_bits_data,
    const uint8_t  *smem_req_bits_mask,
    const uint8_t  *smem_resp_valid,
    const uint32_t *smem_resp_bits_tag,
    const uint32_t *smem_resp_bits_data
) {
  cyclotron_trace_rs(
    cluster_id,
    core_id,
    cycle,
    trace_valid,
    trace_pc,
    trace_warpId,
    trace_tmask,
    trace_regs_0_enable,
    trace_regs_0_address,
    trace_regs_0_data,
    trace_regs_1_enable,
    trace_regs_1_address,
    trace_regs_1_data,
    trace_regs_2_enable,
    trace_regs_2_address,
    trace_regs_2_data,
    dmem_req_valid,
    dmem_req_bits_store,
    dmem_req_bits_address,
    dmem_req_bits_size,
    dmem_req_bits_tag,
    dmem_req_bits_data,
    dmem_req_bits_mask,
    dmem_resp_valid,
    dmem_resp_bits_tag,
    dmem_resp_bits_data,
    smem_req_valid,
    smem_req_bits_store,
    smem_req_bits_address,
    smem_req_bits_size,
    smem_req_bits_tag,
    smem_req_bits_data,
    smem_req_bits_mask,
    smem_resp_valid,
    smem_resp_bits_tag,
    smem_resp_bits_data
  );
}

void cyclotron_difftest_reg_rs(
    const uint8_t   trace_sim_tick,
    const uint32_t  cluster_id,
    const uint32_t  core_id,
    const uint8_t   trace_valid,
    const uint32_t  trace_pc,
    const uint32_t  trace_warpId,
    const uint32_t  trace_tmask,
    const uint8_t   trace_regs_0_enable,
    const uint32_t  trace_regs_0_address,
    const uint32_t *trace_regs_0_data,
    const uint8_t   trace_regs_1_enable,
    const uint32_t  trace_regs_1_address,
    const uint32_t *trace_regs_1_data,
    const uint8_t   trace_regs_2_enable,
    const uint32_t  trace_regs_2_address,
    const uint32_t *trace_regs_2_data
);

void cyclotron_difftest_reg(
    const uint8_t   trace_sim_tick,
    const uint32_t  cluster_id,
    const uint32_t  core_id,
    const uint8_t   trace_valid,
    const uint32_t  trace_pc,
    const uint32_t  trace_warpId,
    const uint32_t  trace_tmask,
    const uint8_t   trace_regs_0_enable,
    const uint32_t  trace_regs_0_address,
    const uint32_t *trace_regs_0_data,
    const uint8_t   trace_regs_1_enable,
    const uint32_t  trace_regs_1_address,
    const uint32_t *trace_regs_1_data,
    const uint8_t   trace_regs_2_enable,
    const uint32_t  trace_regs_2_address,
    const uint32_t *trace_regs_2_data
) {
  cyclotron_difftest_reg_rs(
    trace_sim_tick,
    cluster_id,
    core_id,
    trace_valid,
    trace_pc,
    trace_warpId,
    trace_tmask,
    trace_regs_0_enable,
    trace_regs_0_address,
    trace_regs_0_data,
    trace_regs_1_enable,
    trace_regs_1_address,
    trace_regs_1_data,
    trace_regs_2_enable,
    trace_regs_2_address,
    trace_regs_2_data
  );
}

void profile_perf_counters_rs(
    uint32_t cluster_id,
    uint32_t core_id,
    uint64_t inst_retired,
    uint64_t cycle,
    uint64_t cycles_decoded,
    uint64_t cycles_dispatched,
    uint64_t cycles_eligible,
    uint64_t cycles_issued,
    uint64_t acc_rs_occupancy,
    const uint64_t* per_warp_cycles_decoded,
    const uint64_t* per_warp_cycles_dispatched,
    const uint64_t* per_warp_cycles_eligible,
    const uint64_t* per_warp_cycles_issued,
    const uint64_t* per_warp_stalls_waw,
    const uint64_t* per_warp_stalls_war,
    const uint64_t* per_warp_stalls_scoreboard,
    const uint64_t* per_warp_stalls_rs_full,
    const uint64_t* per_warp_stalls_busy,
    const uint64_t* per_warp_stalls_busy_lsu,
    uint8_t finished
);

void profile_perf_counters(
    uint32_t cluster_id,
    uint32_t core_id,
    uint64_t inst_retired,
    uint64_t cycle,
    uint64_t cycles_decoded,
    uint64_t cycles_dispatched,
    uint64_t cycles_eligible,
    uint64_t cycles_issued,
    uint64_t acc_rs_occupancy,
    const uint64_t* per_warp_cycles_decoded,
    const uint64_t* per_warp_cycles_dispatched,
    const uint64_t* per_warp_cycles_eligible,
    const uint64_t* per_warp_cycles_issued,
    const uint64_t* per_warp_stalls_waw,
    const uint64_t* per_warp_stalls_war,
    const uint64_t* per_warp_stalls_scoreboard,
    const uint64_t* per_warp_stalls_rs_full,
    const uint64_t* per_warp_stalls_busy,
    const uint64_t* per_warp_stalls_busy_lsu,
    uint8_t finished
) {
  profile_perf_counters_rs(
      cluster_id, core_id, inst_retired, cycle, cycles_decoded, cycles_dispatched,
      cycles_eligible, cycles_issued, acc_rs_occupancy, per_warp_cycles_decoded, per_warp_cycles_dispatched, per_warp_cycles_eligible, per_warp_cycles_issued,
      per_warp_stalls_waw, per_warp_stalls_war, per_warp_stalls_scoreboard, per_warp_stalls_rs_full,
      per_warp_stalls_busy,
      per_warp_stalls_busy_lsu, finished);
}

#define CYCLOTRON_LSU_INIT_ARGS \
    const uint32_t *cluster_id, \
    const uint32_t *core_id, \
    int arch_len, \
    int num_warps, \
    int num_lanes, \
    int num_lsu_lanes, \
    int cluster_id_bits, \
    int core_id_bits, \
    int warp_id_bits, \
    int token_bits, \
    int address_space_bits, \
    int mem_op_bits, \
    int preg_bits, \
    int packet_bits, \
    int source_id_bits, \
    int per_lane_mask_bits, \
    int debug_id_bits, \
    int debug_id_port_bits

#define CYCLOTRON_LSU_INIT_PASS \
    cluster_id, \
    core_id, \
    arch_len, \
    num_warps, \
    num_lanes, \
    num_lsu_lanes, \
    cluster_id_bits, \
    core_id_bits, \
    warp_id_bits, \
    token_bits, \
    address_space_bits, \
    mem_op_bits, \
    preg_bits, \
    packet_bits, \
    source_id_bits, \
    per_lane_mask_bits, \
    debug_id_bits, \
    debug_id_port_bits

#define CYCLOTRON_LSU_EVAL_ARGS \
    const uint32_t *cluster_id, \
    const uint32_t *core_id, \
    const uint32_t *coreReservations_req_valid, \
    const uint32_t *coreReservations_req_bits_addressSpace, \
    const uint32_t *coreReservations_req_bits_op, \
    const uint32_t *coreReservations_req_bits_debugId, \
    uint8_t coreReq_valid, \
    const uint32_t *coreReq_bits_token, \
    const uint32_t *coreReq_bits_op, \
    const uint32_t *coreReq_bits_tmask, \
    const uint32_t *coreReq_bits_address, \
    const uint32_t *coreReq_bits_imm, \
    const uint32_t *coreReq_bits_destReg, \
    const uint32_t *coreReq_bits_storeData, \
    uint8_t coreResp_ready, \
    uint8_t globalMemReq_ready, \
    uint8_t globalMemResp_valid, \
    const uint32_t *globalMemResp_bits_tag, \
    const uint32_t *globalMemResp_bits_valid, \
    const uint32_t *globalMemResp_bits_data, \
    uint8_t shmemReq_ready, \
    uint8_t shmemResp_valid, \
    const uint32_t *shmemResp_bits_tag, \
    const uint32_t *shmemResp_bits_valid, \
    const uint32_t *shmemResp_bits_data, \
    uint32_t *coreReservations_req_ready, \
    uint32_t *coreReservations_resp_valid, \
    uint32_t *coreReservations_resp_bits_token, \
    uint8_t *coreReq_ready, \
    uint8_t *coreResp_valid, \
    uint32_t *coreResp_bits_warpId, \
    uint32_t *coreResp_bits_packet, \
    uint32_t *coreResp_bits_tmask, \
    uint32_t *coreResp_bits_destReg, \
    uint32_t *coreResp_bits_writebackData, \
    uint32_t *coreResp_bits_debugId, \
    uint8_t *globalMemReq_valid, \
    uint32_t *globalMemReq_bits_tag, \
    uint32_t *globalMemReq_bits_op, \
    uint32_t *globalMemReq_bits_address, \
    uint32_t *globalMemReq_bits_data, \
    uint32_t *globalMemReq_bits_mask, \
    uint32_t *globalMemReq_bits_tmask, \
    uint8_t *globalMemResp_ready, \
    uint8_t *shmemReq_valid, \
    uint32_t *shmemReq_bits_tag, \
    uint32_t *shmemReq_bits_op, \
    uint32_t *shmemReq_bits_address, \
    uint32_t *shmemReq_bits_data, \
    uint32_t *shmemReq_bits_mask, \
    uint32_t *shmemReq_bits_tmask, \
    uint8_t *shmemResp_ready, \
    uint8_t *sharedQueuesEmpty, \
    uint8_t *globalQueuesEmpty

#define CYCLOTRON_LSU_EVAL_PASS \
    cluster_id, \
    core_id, \
    coreReservations_req_valid, \
    coreReservations_req_bits_addressSpace, \
    coreReservations_req_bits_op, \
    coreReservations_req_bits_debugId, \
    coreReq_valid, \
    coreReq_bits_token, \
    coreReq_bits_op, \
    coreReq_bits_tmask, \
    coreReq_bits_address, \
    coreReq_bits_imm, \
    coreReq_bits_destReg, \
    coreReq_bits_storeData, \
    coreResp_ready, \
    globalMemReq_ready, \
    globalMemResp_valid, \
    globalMemResp_bits_tag, \
    globalMemResp_bits_valid, \
    globalMemResp_bits_data, \
    shmemReq_ready, \
    shmemResp_valid, \
    shmemResp_bits_tag, \
    shmemResp_bits_valid, \
    shmemResp_bits_data, \
    coreReservations_req_ready, \
    coreReservations_resp_valid, \
    coreReservations_resp_bits_token, \
    coreReq_ready, \
    coreResp_valid, \
    coreResp_bits_warpId, \
    coreResp_bits_packet, \
    coreResp_bits_tmask, \
    coreResp_bits_destReg, \
    coreResp_bits_writebackData, \
    coreResp_bits_debugId, \
    globalMemReq_valid, \
    globalMemReq_bits_tag, \
    globalMemReq_bits_op, \
    globalMemReq_bits_address, \
    globalMemReq_bits_data, \
    globalMemReq_bits_mask, \
    globalMemReq_bits_tmask, \
    globalMemResp_ready, \
    shmemReq_valid, \
    shmemReq_bits_tag, \
    shmemReq_bits_op, \
    shmemReq_bits_address, \
    shmemReq_bits_data, \
    shmemReq_bits_mask, \
    shmemReq_bits_tmask, \
    shmemResp_ready, \
    sharedQueuesEmpty, \
    globalQueuesEmpty

#define CYCLOTRON_LSU_COMMIT_ARGS \
    const uint32_t *cluster_id, \
    const uint32_t *core_id, \
    const uint32_t *coreReservations_req_valid, \
    const uint32_t *coreReservations_req_bits_addressSpace, \
    const uint32_t *coreReservations_req_bits_op, \
    const uint32_t *coreReservations_req_bits_debugId, \
    uint8_t coreReq_valid, \
    const uint32_t *coreReq_bits_token, \
    const uint32_t *coreReq_bits_op, \
    const uint32_t *coreReq_bits_tmask, \
    const uint32_t *coreReq_bits_address, \
    const uint32_t *coreReq_bits_imm, \
    const uint32_t *coreReq_bits_destReg, \
    const uint32_t *coreReq_bits_storeData, \
    uint8_t coreResp_ready, \
    uint8_t globalMemReq_ready, \
    uint8_t globalMemResp_valid, \
    const uint32_t *globalMemResp_bits_tag, \
    const uint32_t *globalMemResp_bits_valid, \
    const uint32_t *globalMemResp_bits_data, \
    uint8_t shmemReq_ready, \
    uint8_t shmemResp_valid, \
    const uint32_t *shmemResp_bits_tag, \
    const uint32_t *shmemResp_bits_valid, \
    const uint32_t *shmemResp_bits_data, \
    const uint32_t *coreReservations_req_ready, \
    const uint32_t *coreReservations_resp_valid, \
    const uint32_t *coreReservations_resp_bits_token, \
    uint8_t coreReq_ready, \
    uint8_t coreResp_valid, \
    const uint32_t *coreResp_bits_warpId, \
    const uint32_t *coreResp_bits_packet, \
    const uint32_t *coreResp_bits_tmask, \
    const uint32_t *coreResp_bits_destReg, \
    const uint32_t *coreResp_bits_writebackData, \
    const uint32_t *coreResp_bits_debugId, \
    uint8_t globalMemReq_valid, \
    const uint32_t *globalMemReq_bits_tag, \
    const uint32_t *globalMemReq_bits_op, \
    const uint32_t *globalMemReq_bits_address, \
    const uint32_t *globalMemReq_bits_data, \
    const uint32_t *globalMemReq_bits_mask, \
    const uint32_t *globalMemReq_bits_tmask, \
    uint8_t globalMemResp_ready, \
    uint8_t shmemReq_valid, \
    const uint32_t *shmemReq_bits_tag, \
    const uint32_t *shmemReq_bits_op, \
    const uint32_t *shmemReq_bits_address, \
    const uint32_t *shmemReq_bits_data, \
    const uint32_t *shmemReq_bits_mask, \
    const uint32_t *shmemReq_bits_tmask, \
    uint8_t shmemResp_ready, \
    uint8_t sharedQueuesEmpty, \
    uint8_t globalQueuesEmpty

#define CYCLOTRON_LSU_COMMIT_PASS \
    cluster_id, \
    core_id, \
    coreReservations_req_valid, \
    coreReservations_req_bits_addressSpace, \
    coreReservations_req_bits_op, \
    coreReservations_req_bits_debugId, \
    coreReq_valid, \
    coreReq_bits_token, \
    coreReq_bits_op, \
    coreReq_bits_tmask, \
    coreReq_bits_address, \
    coreReq_bits_imm, \
    coreReq_bits_destReg, \
    coreReq_bits_storeData, \
    coreResp_ready, \
    globalMemReq_ready, \
    globalMemResp_valid, \
    globalMemResp_bits_tag, \
    globalMemResp_bits_valid, \
    globalMemResp_bits_data, \
    shmemReq_ready, \
    shmemResp_valid, \
    shmemResp_bits_tag, \
    shmemResp_bits_valid, \
    shmemResp_bits_data, \
    coreReservations_req_ready, \
    coreReservations_resp_valid, \
    coreReservations_resp_bits_token, \
    coreReq_ready, \
    coreResp_valid, \
    coreResp_bits_warpId, \
    coreResp_bits_packet, \
    coreResp_bits_tmask, \
    coreResp_bits_destReg, \
    coreResp_bits_writebackData, \
    coreResp_bits_debugId, \
    globalMemReq_valid, \
    globalMemReq_bits_tag, \
    globalMemReq_bits_op, \
    globalMemReq_bits_address, \
    globalMemReq_bits_data, \
    globalMemReq_bits_mask, \
    globalMemReq_bits_tmask, \
    globalMemResp_ready, \
    shmemReq_valid, \
    shmemReq_bits_tag, \
    shmemReq_bits_op, \
    shmemReq_bits_address, \
    shmemReq_bits_data, \
    shmemReq_bits_mask, \
    shmemReq_bits_tmask, \
    shmemResp_ready, \
    sharedQueuesEmpty, \
    globalQueuesEmpty

void cyclotron_lsu_init_rs(CYCLOTRON_LSU_INIT_ARGS);
void cyclotron_lsu_reset_rs(const uint32_t *cluster_id, const uint32_t *core_id);
void cyclotron_lsu_eval_rs(CYCLOTRON_LSU_EVAL_ARGS);
void cyclotron_lsu_commit_rs(CYCLOTRON_LSU_COMMIT_ARGS);

void cyclotron_lsu_init(CYCLOTRON_LSU_INIT_ARGS) {
  cyclotron_lsu_init_rs(CYCLOTRON_LSU_INIT_PASS);
}

void cyclotron_lsu_reset(const uint32_t *cluster_id, const uint32_t *core_id) {
  cyclotron_lsu_reset_rs(cluster_id, core_id);
}

void cyclotron_lsu_eval(CYCLOTRON_LSU_EVAL_ARGS) {
  cyclotron_lsu_eval_rs(CYCLOTRON_LSU_EVAL_PASS);
}

void cyclotron_lsu_commit(CYCLOTRON_LSU_COMMIT_ARGS) {
  cyclotron_lsu_commit_rs(CYCLOTRON_LSU_COMMIT_PASS);
}

#undef CYCLOTRON_LSU_INIT_ARGS
#undef CYCLOTRON_LSU_INIT_PASS
#undef CYCLOTRON_LSU_EVAL_ARGS
#undef CYCLOTRON_LSU_EVAL_PASS
#undef CYCLOTRON_LSU_COMMIT_ARGS
#undef CYCLOTRON_LSU_COMMIT_PASS

} // extern "C"
