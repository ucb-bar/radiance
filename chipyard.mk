##############################################################
# extra variables/targets ingested by the chipyard make system
##############################################################

VORTEX_SRC_DIR = $(base_dir)/generators/radiance/src/main/resources/vsrc/vortex CYCLOTRON_SRC_DIR = $(base_dir)/generators/radiance/cyclotron
CYCLOTRON_BUILD_DIR = $(CYCLOTRON_SRC_DIR)/target/debug
# CYCLOTRON_BUILD_DIR = $(CYCLOTRON_SRC_DIR)/target/release
RADIANCE_CSRC_DIR = $(base_dir)/generators/radiance/src/main/resources/csrc
RADIANCE_VSRC_DIR = $(base_dir)/generators/radiance/src/main/resources/vsrc

##################################################################
# THE FOLLOWING MUST BE += operators
##################################################################

# EXTRA_SIM_REQS += cyclotron
# EXTRA_SIM_LDFLAGS += -L$(CYCLOTRON_BUILD_DIR) -Wl,-rpath,$(CYCLOTRON_BUILD_DIR) -lcyclotron
ifeq ($(shell echo $(CONFIG) | grep -E "SynConfig$$"),$(CONFIG))
    EXTRA_SIM_PREPROC_DEFINES += +define+SYNTHESIS +define+NDEBUG +define+DPI_DISABLE
endif
ifeq ($(shell echo $(CONFIG) | grep -E "FP16Config$$"),$(CONFIG))
    EXTRA_SIM_PREPROC_DEFINES += +define+NUM_CORES=8
endif
ifeq ($(shell echo $(CONFIG) | grep -E "HopperConfig$$"),$(CONFIG))
    EXTRA_SIM_PREPROC_DEFINES += +define+NUM_CORES=4 +define+EXT_T_HOPPER
endif
ifeq ($(shell echo $(CONFIG) | grep -E "FlashConfig$$"),$(CONFIG))
    EXTRA_SIM_PREPROC_DEFINES += +define+NUM_CORES=4
endif
EXTRA_SIM_PREPROC_DEFINES += \
	+define+SIMULATION \
	+define+GPR_RESET \
	+define+GPR_DUPLICATED \
	+define+DBG_TRACE_CORE_PIPELINE_VCS \
	+define+PERF_ENABLE \
	+define+ICACHE_DISABLE +define+DCACHE_DISABLE \
	+define+GBAR_ENABLE \
	+define+GBAR_CLUSTER_ENABLE \
	+define+FPU_FPNEW
	# +define+LSU_DUP_DISABLE \

EXT_INCDIRS += \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/vortex/third_party/fpnew/src/common_cells/include \

VCS_NONCC_OPTS := \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/vortex/third_party/fpnew/src/fpnew_pkg.sv \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/vortex/third_party/fpnew/src/fpu_div_sqrt_mvp/hdl/defs_div_sqrt_mvp.sv \
	$(VCS_NONCC_OPTS)

ifeq ($(sim_name),verilator)
EXTRA_SIM_PREPROC_DEFINES += \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/vortex/third_party/fpnew/src/fpnew_pkg.sv \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/vortex/third_party/fpnew/src/fpu_div_sqrt_mvp/hdl/defs_div_sqrt_mvp.sv \

EXT_INCDIRS += \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/vortex/third_party/fpnew/src/common_cells/include/common_cells \
	$(GEN_COLLATERAL_DIR) \

endif

VCS_NONCC_OPTS += +vcs+initreg+random

# cargo handles building of Rust files all on its own, so make this a PHONY
# target to run cargo unconditionally
.PHONY: cyclotron
cyclotron:
	cd $(CYCLOTRON_SRC_DIR) && cargo build # --release

# below manipulation of RADIANCE_EXTERNAL_SRCS doesn't work if we try to reuse
# $(call lookup_srcs) from common.mk, the variable doesn't expand somehow
ifeq ($(shell which fdfd 2> /dev/null),)
	# RADIANCE_EXTERNAL_SRCS := $(shell find -L $(VORTEX_SRC_DIR) -type f -iname "*.sv" -o -iname "*.vh" -o -iname "*.v")
	RADIANCE_EXTERNAL_SRCS := $(shell find -L $(RADIANCE_VSRC_DIR) -type f -iname "*.sv" -o -iname "*.vh" -o -iname "*.v")
	RADIANCE_EXTERNAL_SRCS += $(shell find -L $(RADIANCE_CSRC_DIR) -type f)
else
	# RADIANCE_EXTERNAL_SRCS := $(shell fdfind -L -t f -e "sv" -e "vh" -e "v" . $(VORTEX_SRC_DIR))
	RADIANCE_EXTERNAL_SRCS := $(shell fdfind -L -t f -e "sv" -e "vh" -e "v" . $(RADIANCE_VSRC_DIR))
	RADIANCE_EXTERNAL_SRCS += $(shell fdfind -L -t f . $(RADIANCE_CSRC_DIR))
endif

# for debug; check if expanded
# $(info RADIANCE_EXTERNAL_SRCS: $(RADIANCE_EXTERNAL_SRCS))

# For every Vortex verilog source file, if there's a matching file in
# gen-collateral/, copy them over to keep up-to-dateness.
#
# This is used when you intentionally skip searching for `vortex` directory in
# `CHIPYARD_VLOG_SOURCES` in common.mk (via --exclude vortex).  This is useful
# to prevent vortex file changes from triggering full Chisel re-build, but it
# needs to be used in conjunction with this gen-collateral diff trick by
# un-commenting the following line:
# EXTRA_SIM_REQS += vortex_vsrc

.PHONY: vortex_vsrc
vortex_vsrc: $(RADIANCE_EXTERNAL_SRCS)
	@for file in $(RADIANCE_EXTERNAL_SRCS); do \
		filename=$$(basename "$$file"); \
		if [ -f $(GEN_COLLATERAL_DIR)/$$filename ]; then \
			if ! diff $$file $(GEN_COLLATERAL_DIR)/$$filename &>/dev/null ; then \
				cp -v "$$file" $(GEN_COLLATERAL_DIR); \
			fi; \
		fi; \
	done
