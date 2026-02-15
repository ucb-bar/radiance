##############################################################
# extra variables/targets ingested by the chipyard make system
##############################################################


##################################################################
# Cyclotron options
##################################################################

CYCLOTRON_SRC_DIR = $(base_dir)/generators/radiance/cyclotron
CYCLOTRON_BUILD_DIR = $(CYCLOTRON_SRC_DIR)/target/debug
CYCLOTRON_LIB = $(CYCLOTRON_BUILD_DIR)/libcyclotron.so
CYCLOTRON_RS_SRCS = $(shell find $(CYCLOTRON_SRC_DIR)/src -name "*.rs")
CYCLOTRON_CARGO_FILES = $(CYCLOTRON_SRC_DIR)/Cargo.toml \
			$(wildcard $(CYCLOTRON_SRC_DIR)/Cargo.lock)

# detect cargo at parse time
HAVE_CARGO := $(shell command -v cargo >/dev/null 2>&1 && echo 1 || echo 0)

.PHONY: cyclotron
ifeq ($(HAVE_CARGO),1)

cyclotron: $(CYCLOTRON_LIB)

$(CYCLOTRON_LIB): $(CYCLOTRON_RS_SRCS) $(CYCLOTRON_CARGO_FILES)
	cd $(CYCLOTRON_SRC_DIR) && cargo build # --release

EXTRA_SIM_REQS    += | $(CYCLOTRON_LIB)
EXTRA_SIM_LDFLAGS += -L$(CYCLOTRON_BUILD_DIR) -Wl,-rpath,$(CYCLOTRON_BUILD_DIR) -lcyclotron

else

# no cargo: don't try to build or link it
cyclotron:
	@echo "cargo not found; skipping cyclotron build/link"

endif

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

# go brrr
VCS_NONCC_OPTS += -j$(shell nproc)
