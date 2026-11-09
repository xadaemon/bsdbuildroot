#
# Builds the FreeBSD image for Sedna on a remote FreeBSD host and copies the
# results back to output/images. See README.md.
#
#   make                      build everything, fetch the images
#   make stages S="kernel rootfs"
#                             run only some stages of scripts/build.sh
#   make push | pull          copy the tree there | the images back
#   make shell                ssh into the build directory
#   make test                 boot the images in Sedna (needs Java), see test/
#   make datapack             OC2 data pack with the disk image, in output/
#   make install-datapack MC_WORLD=<.../saves/World>
#                             copy it into a Minecraft world
#

# Settings for this checkout, e.g. BUILD_HOST, go in local.mk (not tracked).
-include local.mk

# The FreeBSD build host, as for ssh: [user@]host.
BUILD_HOST	?=
REMOTE_DIR	?= bsdbuildroot
# The board firmware is shared with the Linux tree, it is built from there.
SEDNA_FW	?= ../buildroot/boot/sedna-fw/src

SSH		?= ssh -o BatchMode=yes
IMAGES		:= output/images

# Never send macOS metadata (._* files) along.
export COPYFILE_DISABLE := 1

PUSH_FILES	:= Makefile README.md configs scripts board

.PHONY: all build stages push pull shell test datapack install-datapack clean

all: build

# Everything that talks to the build host.
REMOTE_TARGETS	:= build stages push pull shell
ifneq ($(filter $(REMOTE_TARGETS) all,$(or $(MAKECMDGOALS),all)),)
ifeq ($(BUILD_HOST),)
$(error BUILD_HOST is not set: put BUILD_HOST = user@host in local.mk, or pass it to make)
endif
endif

build: push
	$(SSH) $(BUILD_HOST) 'cd $(REMOTE_DIR) && sh scripts/build.sh all'
	$(MAKE) pull

stages: push
	$(SSH) $(BUILD_HOST) 'cd $(REMOTE_DIR) && sh scripts/build.sh $(S)'
	$(MAKE) pull

push:
	tar -cf - $(PUSH_FILES) | $(SSH) $(BUILD_HOST) \
	    'mkdir -p $(REMOTE_DIR) && cd $(REMOTE_DIR) && \
	     rm -rf $(PUSH_FILES) fw && tar -xf -'
	@if [ -f "$(SEDNA_FW)/fw.ld" ]; then \
	    tar -C "$(SEDNA_FW)" -cf - . | $(SSH) $(BUILD_HOST) \
	        'mkdir -p $(REMOTE_DIR)/fw/sedna-fw && \
	         tar -xf - -C $(REMOTE_DIR)/fw/sedna-fw'; \
	else \
	    echo "note: no sedna-fw sources at $(SEDNA_FW), firmware not built"; \
	fi

pull:
	mkdir -p $(IMAGES)
	$(SSH) $(BUILD_HOST) 'cd $(REMOTE_DIR)/output/images && tar -cf - .' | \
	    tar -xf - -C $(IMAGES)
	@ls -l $(IMAGES)

shell:
	$(SSH) -t $(BUILD_HOST) 'cd $(REMOTE_DIR) && exec $$SHELL -l'

test:
	$(MAKE) -C test run IMAGES=$(CURDIR)/$(IMAGES)

# A Minecraft data pack for OpenComputers II: the image becomes a hard drive
# in the creative tab ("Sedna FreeBSD"), booted by OC2's stock Sedna firmware.
DATAPACK	:= output/sedna-freebsd-datapack.zip
DATAPACK_HDD	:= data/sedna_freebsd/block_devices/hdd

datapack: $(DATAPACK)

$(DATAPACK): $(IMAGES)/sedna-freebsd.img datapack/pack.mcmeta datapack/$(DATAPACK_HDD)/freebsd.json
	rm -rf output/datapack $@
	mkdir -p output/datapack
	cp -R datapack/ output/datapack/
	cp $(IMAGES)/sedna-freebsd.img output/datapack/$(DATAPACK_HDD)/freebsd.bin
	cd output/datapack && zip -qrX ../$(notdir $@) pack.mcmeta data
	@echo "$@: copy to <world>/datapacks/, then /reload"

install-datapack: $(DATAPACK)
	@[ -d "$(MC_WORLD)" ] || { echo "set MC_WORLD to a world directory (.../saves/<world>)"; exit 1; }
	mkdir -p "$(MC_WORLD)/datapacks"
	cp $(DATAPACK) "$(MC_WORLD)/datapacks/"
	@echo "installed; in game: /reload (or rejoin the world)"

clean:
	rm -rf output
