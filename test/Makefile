#
# Boots the images in output/images on the Sedna emulator, on this machine,
# with the board set up like an OC2 computer (4 x 8 MB memory, OC2's boot
# arguments, the system controller interrupt). Needs git and a JDK (21+).
#
#   make run                 interactive, console on stdio (^C quits)
#   make smoke               scripted boot: run a few commands, then ask the
#                            guest to power off through the system controller
#   make oc2-firmware OC2_JAR=.../oc2-*.jar
#                            extract the sedna-fw an OC2 jar ships, to boot
#                            with exactly what runs in game: FIRMWARE=<printed path>
#

SEDNA_REPO	?= https://github.com/fnuecke/sedna.git
SEDNA_REF	?= dfe9dfe
JAVA_VERSION	?= $(shell java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')

IMAGES		?= $(CURDIR)/../output/images
FIRMWARE	?= $(IMAGES)/firmware.bin
MEMORY		?= 8,8,8,8
BOOTARGS	?= root=/dev/vda rw uio_pdrv_genirq.of_id=oc2,mlapi
BUILD		:= $(CURDIR)/build
LIB		:= $(BUILD)/lib
STAMP		:= $(LIB)/.sedna-$(SEDNA_REF)

RUN = java -cp '$(LIB)/*' $(CURDIR)/SednaRun.java \
	--firmware $(FIRMWARE) --disk $(BUILD)/disk.img \
	--memory $(MEMORY) --net --bootargs '$(BOOTARGS)'

.PHONY: run smoke sedna disk oc2-firmware clean

run: $(STAMP) disk
	$(RUN) $(ARGS)

# The guest writes to its disk; boot a copy.
disk:
	mkdir -p $(BUILD)
	cp $(IMAGES)/sedna-freebsd.img $(BUILD)/disk.img

smoke: $(STAMP) disk
	$(RUN) --timeout 900 --poweroff-after 5 \
	    --send 'uname -a; df -h /; mount; ifconfig -a | grep -E "^[a-z]"; sysctl hw.physmem\n' \
	    --send 'echo "print(_VERSION)" | lua; ls /mnt; uptime\n' \
	    $(ARGS)

sedna: $(STAMP)

oc2-firmware:
	@mkdir -p $(BUILD)/oc2-fw
	@unzip -q -o -j "$(OC2_JAR)" generated/firmware.bin generated/firmware.properties \
	    -d $(BUILD)/oc2-fw
	@echo $(BUILD)/oc2-fw/firmware.bin

$(STAMP):
	mkdir -p $(BUILD) $(LIB)
	[ -d $(BUILD)/sedna/.git ] || git clone -q $(SEDNA_REPO) $(BUILD)/sedna
	git -C $(BUILD)/sedna checkout -q $(SEDNA_REF)
	cd $(BUILD)/sedna && ./gradlew -q --console=plain \
	    -I $(CURDIR)/sedna-libs.init.gradle -Dsedna.libdir=$(LIB) \
	    -Dsedna.java=$(JAVA_VERSION) sednaRuntimeLibs
	touch $@

clean:
	rm -rf $(BUILD)
