##############################################################
# extra variables/targets ingested by the chipyard make system
##############################################################


##################################################################
# Cyclotron options
##################################################################

CYCLOTRON_SRC_DIR = $(base_dir)/generators/radiance/cyclotron
CYCLOTRON_BUILD_DIR = $(CYCLOTRON_SRC_DIR)/target/debug

# cargo handles building of Rust files all on its own, so make this a PHONY
# target to run cargo unconditionally
.PHONY: cyclotron
cyclotron:
	cd $(CYCLOTRON_SRC_DIR) && cargo build # --release

EXTRA_SIM_REQS += cyclotron
EXTRA_SIM_LDFLAGS += -L$(CYCLOTRON_BUILD_DIR) -Wl,-rpath,$(CYCLOTRON_BUILD_DIR) -lcyclotron


EXT_INCDIRS += \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/cvfpu/src/common_cells/include \

VCS_NONCC_OPTS := \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/cvfpu/src/fpnew_pkg.sv \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/cvfpu/src/fpu_div_sqrt_mvp/hdl/defs_div_sqrt_mvp.sv \
	$(VCS_NONCC_OPTS)

ifeq ($(sim_name),verilator)
EXTRA_SIM_PREPROC_DEFINES += \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/cvfpu/src/fpnew_pkg.sv \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/cvfpu/src/fpu_div_sqrt_mvp/hdl/defs_div_sqrt_mvp.sv \

EXT_INCDIRS += \
	$(base_dir)/generators/radiance/src/main/resources/vsrc/cvfpu/src/common_cells \
	$(GEN_COLLATERAL_DIR) \

endif

VCS_NONCC_OPTS += +vcs+initreg+random

