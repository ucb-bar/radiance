##############################################################
# extra variables/targets ingested by the chipyard make system
##############################################################

##################################################################
# THE FOLLOWING MUST BE += operators
##################################################################

RADPIE_SRC_DIR = $(base_dir)/generators/radiance/radpie
RADPIE_BUILD_DIR = $(RADPIE_SRC_DIR)/target/release

# EXTRA_SIM_REQS += radpie
EXTRA_SIM_LDFLAGS += -L$(RADPIE_BUILD_DIR) -Wl,-rpath,$(RADPIE_BUILD_DIR) -lradpie
EXTRA_SIM_PREPROC_DEFINES += \
	+define+SIMULATION \
	+define+SV_DPI \
	+define+GPR_RESET \
	+define+LSU_DUP_DISABLE \
	+define+DBG_TRACE_CORE_PIPELINE_VCS \
	+define+PERF_ENABLE \
	+define+ICACHE_DISABLE +define+DCACHE_DISABLE \
	+define+GBAR_ENABLE \
	+define+GBAR_CLUSTER_ENABLE \
	+define+NUM_BARRIERS=8 \
	+define+NUM_CORES=2 +define+NUM_THREADS=8 +define+NUM_WARPS=8
	# Can't increase this to above 14, since the binary accesses 0xff0040..
	# which is unmapped to any memory
	# +define+SMEM_LOG_SIZE=14 \

# cargo handles building of Rust files all on its own, so make this a PHONY
# target to run cargo unconditionally
.PHONY: radpie
radpie:
	cd $(RADPIE_SRC_DIR) && cargo build --release
