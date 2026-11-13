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
# Power BSD, on javapower's PAPR machine set up like OC2's tier 5 computer
# (the javapower release OC2 bundles, from Maven Central; 256 MB, 1 CPU,
# OC2's speed):
#
#   make power-run           interactive, console on stdio (^C quits)
#   make power-smoke         scripted boot: log in, run a few commands, power off
#   make power-stripe-smoke  three more drives as one striped volume
#                            (gstripe), which is back after a restart
#   make power-oc2-smoke     the same with OC2's device bus and a serial
#                            interface card (--oc2 --serial): the mid-level
#                            API through mlapi(1), the high-level API through
#                            OC2's Lua libraries
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

.PHONY: run smoke sedna disk oc2-firmware power-run power-smoke power-oc2-smoke power-stripe-smoke \
	power-disk clean

run: $(STAMP) disk
	$(RUN) $(ARGS)

# The guest writes to its disk; boot a copy.
disk:
	mkdir -p $(BUILD)
	cp $(IMAGES)/sedna-freebsd.img $(BUILD)/disk.img

smoke: $(STAMP) disk
	$(RUN) --timeout 900 --poweroff-after 5 \
	    --wait-for 'login: ' --send 'root\n' --wait-for '# ' \
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

JAVAPOWER_VERSION ?= 0.3.1
JAVAPOWER_MODULES := core cpu-int cpu-fpvec platform emulator firmware-slof
MAVEN		?= https://repo1.maven.org/maven2
POWER_IMAGES	?= $(CURDIR)/../output/power-ppc64le/images
POWER_LIB	:= $(BUILD)/javapower-$(JAVAPOWER_VERSION)
POWER_STAMP	:= $(POWER_LIB)/.done
# Guest RAM is on the heap on Java 21, off it (direct memory) later.
POWER_RUN = java -Xmx1g -XX:MaxDirectMemorySize=1g -cp '$(POWER_LIB)/*' \
	$(CURDIR)/PowerRun.java --disk $(BUILD)/power-disk.img

power-run: $(POWER_STAMP) power-disk
	$(POWER_RUN) $(ARGS)

power-smoke: $(POWER_STAMP) power-disk
	$(POWER_RUN) --timeout 1200 \
	    --wait-for 'login: ' --send 'root\n' --wait-for '# ' \
	    --send 'uname -a; freebsd-version; df -h /; mount; sysctl hw.ncpu hw.physmem\n' \
	    --send 'initdisks; echo "print(_VERSION)" | lua; uptime\n' \
	    --wait-for '# ' --send 'service -e; sysrc kld_list hostname; kldstat; service cron status; service syslogd restart; logger hello; tail -2 /var/log/messages\n' \
	    --wait-for '# ' --send 'poweroff\n' \
	    $(ARGS)

# What the emulated devices answer is in PowerRun.java: a redstone interface
# ("redstone", REDSTN, labelled "door") on both APIs, the high-level API's
# "test" (echo returns its payload) and the card's "serial". The redstone
# signal is changed while a program waits for the event, on either API.
power-oc2-smoke: $(POWER_STAMP) power-disk
	$(POWER_RUN) --oc2 --serial --timeout 2400 \
	    --wait-for 'login: ' --send 'root\n' \
	    --wait-for '# ' --send 'grep . /sys/class/virtio-ports/*/name /sys/class/tty/*/iomem_base; ls -l /dev/ttyS1; service oc2busd status\n' \
	    --wait-for '# ' --send 'pciconf -l; mlapi list; mlapi call door 3 1 15; mlapi call REDSTN 1 1; mlapi call -t SYSTEM 1 1 0\n' \
	    --wait-for '# ' --send '(sleep 10; echo wai""ting) & mlapi events -n 1\n' \
	    --wait-for 'waiting' --send '@redstone 2 9' \
	    --wait-for '# ' --send 'lsdev.lua; redstone.lua up; redstone.lua down 4\n' \
	    --wait-for '# ' --send 'lua -e "d = require([[devices]]); print([[wai]] .. [[ting]]); e = d:waitEvent(60000); print(e.type, e.data.side, e.data.value)"\n' \
	    --wait-for 'waiting' --send '@redstone 3 5' \
	    --wait-for '# ' --send 'lua -e "d = require([[devices]]); t = d:find([[test]]); s = string.rep(string.char(13, 0, 255, 10), 4096); print(t:echo(d:blob(s)) == s, pcall(t.fail, t))"\n' \
	    --wait-for '# ' --send 'lua -e "l = assert(require([[oc2.serial]]).open()); l:broadcast([[hello]]); print(l:receive(5000))"\n' \
	    --wait-for '# ' --send 'poweroff\n' \
	    $(ARGS)

# Three more drives, blank, and two boots: a striped volume is made of them
# (gstripe) and mounted; after a restart with the drives in other slots it
# is back, with the file written to it ("kept" is waited for), and then
# taken apart.
POWER_STRIPE	= $(BUILD)/power-stripe
power-stripe-smoke: $(POWER_STAMP) power-disk
	for n in 1 2 3; do \
	    dd if=/dev/zero of=$(POWER_STRIPE)$$n.img bs=1048576 count=8 2>/dev/null; \
	done
	$(POWER_RUN) --disk $(POWER_STRIPE)1.img --disk $(POWER_STRIPE)2.img \
	    --disk $(POWER_STRIPE)3.img --timeout 1200 \
	    --wait-for 'login: ' --send 'root\n' \
	    --wait-for '# ' --send 'kldstat; initdisks; gstripe label data da1 da2 da3 && newfs -U /dev/stripe/data >/dev/null && mountdisks\n' \
	    --wait-for '# ' --send 'df -h /mnt/data; echo kept > /mnt/data/file; initdisks; poweroff\n' \
	    $(ARGS)
	$(POWER_RUN) --disk $(POWER_STRIPE)3.img --disk $(POWER_STRIPE)1.img \
	    --disk $(POWER_STRIPE)2.img --timeout 1200 \
	    --wait-for 'login: ' --send 'root\n' \
	    --wait-for '# ' --send 'gstripe status; df -h /mnt/data; cat /mnt/data/file\n' \
	    --wait-for 'kept' --send 'umount /mnt/data && gstripe stop data && gstripe clear da1 da2 da3; initdisks; service mountdisks restart; mount -p\n' \
	    --wait-for '# ' --send 'poweroff\n' \
	    $(ARGS)

# The guest writes to its disk; boot a copy.
power-disk:
	mkdir -p $(BUILD)
	cp $(POWER_IMAGES)/power-bsd.img $(BUILD)/power-disk.img

$(POWER_STAMP):
	mkdir -p $(POWER_LIB)
	for m in $(JAVAPOWER_MODULES); do \
	    curl -fsSL -o $(POWER_LIB)/javapower-$$m-$(JAVAPOWER_VERSION).jar \
	        $(MAVEN)/org/boxtheta/javapower/javapower-$$m/$(JAVAPOWER_VERSION)/javapower-$$m-$(JAVAPOWER_VERSION).jar \
	        || exit 1; \
	done
	touch $@

clean:
	rm -rf $(BUILD)
