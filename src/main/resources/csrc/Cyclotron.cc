#ifndef NO_VPI
#include <svdpi.h>
#include <vpi_user.h>
#endif
#include <stdint.h>

extern "C" {

void cyclotron_init_rs(int num_lanes);
void cyclotron_init(int num_lanes) { cyclotron_init_rs(num_lanes); }

void cyclotron_frontend_rs(
    const uint8_t* ibuf_ready_vec,
    uint8_t*       ibuf_valid_vec,
    uint32_t*      ibuf_pc_vec,
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
    uint32_t req_address,
    uint32_t req_tag,
    const uint32_t *req_data,
    const uint32_t *req_mask,

    uint8_t resp_ready,
    uint8_t *resp_valid,

    uint32_t *resp_tag,
    uint32_t *resp_data
);

// Note: VCS uses contiguous bits in memory to represent packed bit arrays, 
// this may not be the case on other simulators. 
void cyclotron_mem(
    void *mem_model_ptr,
    
    uint8_t *req_ready,
    uint8_t req_valid,

    uint8_t req_store,
    uint32_t req_address,
    uint32_t req_tag,
    const uint32_t *req_data,
    const uint32_t *req_mask,

    uint8_t resp_ready,
    uint8_t *resp_valid,

    uint32_t *resp_tag,
    uint32_t *resp_data
) {
    cyclotron_mem_rs(
        mem_model_ptr,
        req_ready,
        req_valid,
        req_store,
        req_address,
        req_tag,
        req_data,
        req_mask,
        resp_ready,
        resp_valid,
        resp_tag,
        resp_data
    );
};

} // extern "C"
