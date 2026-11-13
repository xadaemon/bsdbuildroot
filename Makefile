#
# Builds a FreeBSD image for OpenComputers II on a remote FreeBSD host and
# copies the results back to <output>/images. See README.md.
#
# BOARD picks the image, configs/$(BOARD).conf:
#   sedna-riscv64   Sedna FreeBSD, for the RISC-V computers (the default),
#                   results in output/
#   power-ppc64le   Power BSD, for the tier 5 (PowerPC) computer, results in
#                   output/power-ppc64le/
#
#   make                      build everything, fetch the images
#   make stages S="kernel rootfs"
#                             run only some stages of scripts/build.sh
#   make push | pull          copy the tree there | the images back
#   make shell                ssh into the build directory
#   make test                 boot the image in its emulator (needs Java),
#                             see test/
#   make datapack             OC2 data pack with the disk image, in <output>/
#   make install-datapack MC_WORLD=<.../saves/World>
#                             copy it into a Minecraft world
#   make mod                  the same as a Fabric and a NeoForge mod, in
#                             <output>/mod; versioned
#                             <FreeBSD release>-build-<n>
#
# e.g. make BOARD=power-ppc64le, make mod BOARD=power-ppc64le.
#
# powerlinux, the Linux image for the tier 5 computer, has a tree of its own,
# linuxbuildroot.
#

# Settings for this checkout, e.g. BUILD_HOST, go in local.mk (not tracked).
-include local.mk

# The FreeBSD build host, as for ssh: [user@]host.
BUILD_HOST	?=
REMOTE_DIR	?= bsdbuildroot
# The board firmware is shared with the Linux tree, it is built from there.
SEDNA_FW	?= ../buildroot/boot/sedna-fw/src
# OC2's sources: its Lua libraries for the high-level API go into the Power
# BSD image (OC2_SCRIPTS_DIR in its configuration).
OC2_SCRIPTS	?= ../oc2/common/src/main/scripts

SSH		?= ssh -o BatchMode=yes

BOARD		?= sedna-riscv64
CONFIG		:= configs/$(BOARD).conf
ifeq ($(wildcard $(CONFIG)),)
$(error no configuration $(CONFIG) for BOARD=$(BOARD))
endif
# A setting of the configuration: VAR="value" on a line of its own.
conf		= $(shell sed -n 's/^$(1)="\(.*\)"$$/\1/p' $(CONFIG))
NAME		:= $(call conf,NAME)
OUTPUT		:= $(call conf,OUTPUT_DIR)
IMAGES		:= $(OUTPUT)/images
IMAGE		:= $(IMAGES)/$(NAME).img

# Never send macOS metadata (._* files) along.
export COPYFILE_DISABLE := 1

PUSH_FILES	:= Makefile README.md configs scripts board

.PHONY: all build stages push pull shell test datapack install-datapack mod clean

all: build

# Everything that talks to the build host.
REMOTE_TARGETS	:= build stages push pull shell
ifneq ($(filter $(REMOTE_TARGETS) all,$(or $(MAKECMDGOALS),all)),)
ifeq ($(BUILD_HOST),)
$(error BUILD_HOST is not set: put BUILD_HOST = user@host in local.mk, or pass it to make)
endif
endif

build: push
	$(SSH) $(BUILD_HOST) 'cd $(REMOTE_DIR) && CONFIG=$(CONFIG) sh scripts/build.sh all'
	$(MAKE) pull

stages: push
	$(SSH) $(BUILD_HOST) 'cd $(REMOTE_DIR) && CONFIG=$(CONFIG) sh scripts/build.sh $(S)'
	$(MAKE) pull

push:
	tar -cf - $(PUSH_FILES) | $(SSH) $(BUILD_HOST) \
	    'mkdir -p $(REMOTE_DIR) && cd $(REMOTE_DIR) && \
	     rm -rf $(PUSH_FILES) fw oc2 && tar -xf -'
	@if [ -f "$(SEDNA_FW)/fw.ld" ]; then \
	    tar -C "$(SEDNA_FW)" -cf - . | $(SSH) $(BUILD_HOST) \
	        'mkdir -p $(REMOTE_DIR)/fw/sedna-fw && \
	         tar -xf - -C $(REMOTE_DIR)/fw/sedna-fw'; \
	else \
	    echo "note: no sedna-fw sources at $(SEDNA_FW), firmware not built"; \
	fi
	@if [ -f "$(OC2_SCRIPTS)/lib/lua/devices.lua" ]; then \
	    tar -C "$(OC2_SCRIPTS)" -cf - bin lib/lua | $(SSH) $(BUILD_HOST) \
	        'mkdir -p $(REMOTE_DIR)/oc2/scripts && \
	         tar -xf - -C $(REMOTE_DIR)/oc2/scripts'; \
	else \
	    echo "note: no OC2 scripts at $(OC2_SCRIPTS), Power BSD cannot be built"; \
	fi

pull:
	mkdir -p $(IMAGES)
	$(SSH) $(BUILD_HOST) 'cd $(REMOTE_DIR)/$(IMAGES) && tar -cf - .' | \
	    tar -xf - -C $(IMAGES)
	@ls -l $(IMAGES)

shell:
	$(SSH) -t $(BUILD_HOST) 'cd $(REMOTE_DIR) && exec $$SHELL -l'

test:
ifeq ($(BOARD),power-ppc64le)
	$(MAKE) -C test power-run POWER_IMAGES=$(CURDIR)/$(IMAGES)
else
	$(MAKE) -C test run IMAGES=$(CURDIR)/$(IMAGES)
endif

# A Minecraft data pack for OpenComputers II: the image becomes a hard drive
# in the creative tab ("Sedna FreeBSD", "Power BSD"), booted by OC2's own
# firmware. board/$(BOARD)/datapack has its other files; the image goes next
# to the block device's .json, as a .bin of the same name.
PACK_SRC	:= board/$(BOARD)/datapack
PACK_DEVICE	:= $(wildcard $(PACK_SRC)/data/*/block_devices/hdd/*.json)
DATAPACK	:= $(OUTPUT)/$(NAME)-datapack.zip

datapack: $(DATAPACK)

$(DATAPACK): $(IMAGE) $(shell find $(PACK_SRC) -type f ! -name .DS_Store)
	@[ $(words $(PACK_DEVICE)) -eq 1 ] || \
	    { echo "$(PACK_SRC): want one block_devices/hdd/*.json"; exit 1; }
	rm -rf $(OUTPUT)/datapack $@
	mkdir -p $(OUTPUT)/datapack
	cp -R $(PACK_SRC)/ $(OUTPUT)/datapack/
	cp $(IMAGE) $(OUTPUT)/datapack/$(patsubst $(PACK_SRC)/%.json,%.bin,$(PACK_DEVICE))
	cd $(OUTPUT)/datapack && zip -qrX $(CURDIR)/$@ pack.mcmeta data -x '*.DS_Store'
	@echo "$@: copy to <world>/datapacks/, then /reload"

install-datapack: $(DATAPACK)
	@[ -d "$(MC_WORLD)" ] || { echo "set MC_WORLD to a world directory (.../saves/<world>)"; exit 1; }
	mkdir -p "$(MC_WORLD)/datapacks"
	cp $(DATAPACK) "$(MC_WORLD)/datapacks/"
	@echo "installed; in game: /reload (or rejoin the world)"

# The data pack as a mod, mod/$(NAME), for Fabric and NeoForge: it adds the
# pack to every world. The version is the FreeBSD release of the image and the
# image's build number ($(IMAGES)/build-number).
mod: $(IMAGE)
	cd mod && ./gradlew --console=plain -q :$(NAME):dist \
	    -P$(NAME).image=$(CURDIR)/$(IMAGE) -P$(NAME).dist=$(CURDIR)/$(OUTPUT)/mod
	@ls -l $(OUTPUT)/mod

clean:
	rm -rf output
