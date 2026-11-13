# Sedna FreeBSD

FreeBSD for the [Sedna](https://github.com/fnuecke/sedna) RISC-V virtual
machine (OpenComputers II), the FreeBSD counterpart of the Linux tree in
[fnuecke/buildroot](https://github.com/fnuecke/buildroot). Same machine, same, unmodified firmware: the
board firmware (sedna-fw, shipped in the OC2 jar) loads `/boot/Image` from an
ext2 file system on the first bootable virtio disk.

Based on FreeBSD 15.1 (`releng/15.1`).

The tree also makes a second image, [Power BSD](#power-bsd), for OC2's tier 5
computer, the PowerPC mainframe: FreeBSD's own ppc64le release, unchanged,
installed on a small disk, with OC2's high-level and mid-level API.

The disk image is laid out so that sedna-fw finds an ext2 file system while
FreeBSD gets a UFS root (ext2 leaves its first 1024 bytes unused):

```
sector 0      MBR, two FreeBSD (165) partitions: the UFS root and the swap
byte 1024     small ext2 file system with only /boot/Image   (what sedna-fw reads)
next MiB      UFS2 root, soft updates, label sedna_freebsd   (what FreeBSD mounts)
last 16 MiB   swap, glabel label sedna_swap
```

## What is in the image

| Linux image (buildroot)            | FreeBSD image (this tree)                               |
|------------------------------------|---------------------------------------------------------|
| sedna-fw                           | the same sedna-fw, unchanged                            |
| Linux `Image`                      | FreeBSD kernel as a flat binary with a Linux boot image header (patch 0001) |
| ext2 root                          | UFS2 root with soft updates; `fsck -p` at boot          |
| busybox                            | ~90 regular FreeBSD programs with their shared libraries, picked from an installed world ([`userland.list`](board/sedna-riscv64/userland.list)) |
| busybox init + `/etc/init.d/rcS`   | `init(8)` + a small `/etc/rc` running `/etc/rc.d/S??*` (Power BSD: FreeBSD's own `rc(8)`, see [Power BSD](#power-bsd)) |
| getty + login, root without password | the same: getty + `login(1)` (PAM), root without password; `su`, `passwd`, `pw` work |
| busybox syslogd, `/var/log` in RAM | `syslogd(8)`, `/var/log` on tmpfs                       |
| hostname `sedna-xxxxxx` from the NIC's MAC (`S01hostname`) | `localhost`; the Linux behaviour with `TARGET_HOSTNAME_FROM_MAC="yes"`, see [Configuration](#configuration) |
| `S90virtio9p`, `S99mnt_init`       | same scripts, using `p9fs(4)`                           |
| Lua 5.4, luaposix, lua-cjson       | `lua` (FreeBSD's `flua`), its `posix` module extended with the luaposix functions OC2's libraries use (patch 0010), and a `cjson` module |
| OC2's `devices` library and `oc2busd` (high-level API) | the same, unchanged from `/mnt/builtin`, set up by `S91oc2`, see [High-level API](#high-level-api) |
| nano                               | `ee`                                                    |
| (none)                             | `dhclient(8)`, off by default, see [Network](#network); `/root/getting_started.txt`, a short guide to the system |
| mid-level API as `/dev/uioN` (`uio_pdrv_genirq`), `mlapi.h` for tcc | `/dev/mlapi` (patch 0009) with the same interface; `mlapi(1)` to use it from the shell, `mlapi.h` to cross compile, see [Mid-level API](#mid-level-api) |

Kernel ([`board/sedna-riscv64/kernel/SEDNA`](board/sedna-riscv64/kernel/SEDNA)):
one hart, no modules, ns16550 console, virtio-mmio block/net/console/9p,
Goldfish RTC, PLIC, SBI timer, OC2's mid-level API, UFS root mounted by label, ext2fs, tmpfs, DDB.

It runs in OC2's largest configuration, 4 × 8 MB memory: about 25 MB usable
(`hw.physmem`) after the firmware's 2 MB, of which the kernel keeps ~9 MB;
~4 MB is free at the login prompt with oc2busd running. The disk is 40 MB:
5 MB boot area, 19 MB root with ~6 MB free, and 16 MB swap, so programs that
sit idle make room for others: about 18 Lua programs using the high-level
API run at once next to oc2busd (5 without swap and the kernel settings
below).

### Patches to FreeBSD ([`board/sedna-riscv64/patches/freebsd`](board/sedna-riscv64/patches/freebsd))

1. **Linux boot image header** for riscv: the first 64 bytes of the kernel
   become the header sedna-fw (and U-Boot `booti`) expects, with a `j
   _alt_start` as the first instruction, so `objcopy -O binary kernel Image`
   is bootable. OpenSBI `fw_jump` style entry at the load address still works.
2. **Quiet boot without loader**: booting without `loader(8)` forced
   `bootverbose`. `-v` in `/chosen/bootargs` still turns it on.
3. **Sedna system controller driver** (`sedna,syscon`): `poweroff`/`reboot`
   from the guest, and the host's poweroff/reboot requests (interrupt) become
   an orderly `shutdown`, like the Linux `sedna-syscon` driver. sedna-fw has no
   SBI SRST extension, so this is the only way to power off.
4. **All memory nodes**: Sedna describes every memory device (OC2 memory
   stick) as its own `/memory@…` node; FreeBSD only used the first one.
5. **virtio-blk with a small maximum segment size**: Sedna's disks advertise
   16K segments, which vtblk refused outright; it now limits transfers to the
   segment size instead.
6. **Fixed ns8250 FIFO size** (`hw.uart.ns8250_fifo_size`, set in the kernel
   configuration): the FIFO probe relies on loopback mode, which Sedna's UART
   lacks, so it printed a few hundred NULs on the terminal.
7. **virtio-console: no notifications before `DRIVER_OK`**: vtcon kicked
   its receive queues and sent `DEVICE_READY` during attach, before the
   driver may notify the device; Sedna (correctly) ignores that, and boot
   hung at `vtcon0` with OC2's RPC console attached.
8. **virtio-p9fs: finalize feature negotiation**: unlike every other virtio
   driver, p9fs never set `FEATURES_OK`; Sedna's (modern) 9p device then
   never answers, and mounting `/mnt/builtin` hung for 120 seconds.
9. **OC2 mid-level API driver** (`oc2,mlapi`): `/dev/mlapi`, the register
   page and its interrupt, with the interface Linux's `uio_pdrv_genirq`
   gives OC2's image: `mmap` the page, `read` 4 bytes to wait for the
   interrupt, `write` a 32-bit 1 to enable it again, plus `poll`.
10. **flua: the luaposix functions OC2's libraries use**: `posix.fcntl`,
    `posix.poll`, `posix.time`, `posix.errno`, `posix.signal` (with Lua
    handlers), `posix.sys.socket` (AF_UNIX), `posix.dirent`, and `stat`/`mkdir`
    in `posix.sys.stat`, with luaposix's names and calling conventions.
11. **virtio-console: init and lock devices** (`/dev/ttyV*.init`): a tty
    goes back to its `.init` settings on every first open, and vtcon ports
    had none, so they always opened in canonical mode with echo. The HLAPI
    ports need raw mode for every program that opens them.
12. **virtio-console: back-pressure**: vtcon gave every receive buffer back
    to the device at once, so input beyond the tty's ~2 KB queue was
    dropped. It now holds buffers until the reader makes room, as Linux's
    driver does, so large HLAPI payloads arrive intact.
13. **virtio-console: one open at a time per port**: as on Linux, a port of
    a multiport device (not a console port) can only be open once; a second
    `open()` fails with `EBUSY`, for root too. A new tty flag,
    `TF_SINGLEOPEN`, does that (`TIOCEXCL` does not keep root out). Two
    programs on one HLAPI port would read part of each other's replies.
14. **stty `-F`**: Linux's `stty -F /dev/ttyS1 9600 raw`, which OC2's
    serial library and manual use. As `-f`, and the settings also become
    the terminal's initial state (its `.init` device): FreeBSD resets a
    terminal to that state on every first open, Linux keeps the settings.

The `src` stage commits the patches to a local `sedna` branch, with a fixed
author and dates, rather than leaving them as changes in the work tree. With
a clean tree the kernel's version string (`uname -a`) is reproducible: just
`FreeBSD 15.1-RELEASE-p6 sedna-<hash> SEDNA`, without the build user, host
and time that FreeBSD records for modified trees.

The kernel configuration also needs `options KTRACE`: without it, 15.1's
`subr_syscall.c` does not build. It also lowers tmpfs's memory reserve from
4 MB to 512 KB (`TMPFS_PAGES_MINRESERVED`): tmpfs refuses to create files
while fewer pages are free, and 4 MB is most of what is free on this
machine, so `/tmp`, `/var/log` and `/run` failed with "No space left on
device" as soon as a program or two ran. And it scales down what the kernel
sets aside at boot, which is wired memory that can't be swapped out
(13.4 MB of 23.4 MB before, 9 MB now): the buffer cache is capped at 2 MB
(`kern.nbuf=128`; by default it could grow to 6 MB), I/O buffers are sized
for 128 KB transfers instead of 1 MB (`kern.maxphys`), and the device event
queue for `devd(8)`, which does not run, is off (`hw.bus.devctl_queue=0`).

The swap partition is found by its `glabel(8)` label,
`/dev/label/sedna_swap`, whatever the disk is called; `SWAP_SIZE` in the
configuration sets its size, empty for none.

### Not supported yet

- virtio-input (keyboard) and the framebuffer console: FreeBSD has no
  virtio-input driver, so only the serial console (OC2's terminal) works.
- virtio-sound: no FreeBSD driver.
- The firmware's `root=/dev/vdX` rewrite of the bootargs: FreeBSD finds its
  root file system by label (`/dev/ufs/sedna_freebsd`) instead, so don't
  attach two disks with this image at once.
- OC2's `/mnt/builtin/init.d` scripts are skipped (they are written for the
  Linux image); `touch /etc/mnt_init.builtin` runs them anyway. The 9p file
  system itself is mounted at `/mnt/builtin` as usual, and `S91oc2` does
  what the scripts that matter do: `LUA_PATH`, and starting oc2busd.
- The kernel in the boot area can't be updated from FreeBSD; rebuild the
  image instead.

## Power BSD

OC2's tier 5 computer is a PAPR "pseries" machine, run by the
[javapower](https://github.com/boxtheta/javapower) emulator: SLOF (Open
Firmware) as its firmware, up to four POWER9 processors, 256 MB of memory per
64 MB module, its hard drives as disks on a v-scsi bus and its console on the
block's serial port, at 9600 baud. The device bus is hardware of that
machine too: the high-level API on three more consoles, the mid-level API in
a PCI device, see [Device bus](#device-bus).

javapower boots FreeBSD's release as it is, so the Power BSD image builds no
world and no kernel: [`configs/power-ppc64le.conf`](configs/power-ppc64le.conf)
fetches the 15.1-RELEASE `base.txz` and `kernel.txz` for powerpc64le (checked
against the release's MANIFEST), picks a small part of them and installs it
on a 64 MB disk, laid out as FreeBSD's own powerpc64 VM images are:

```
sector 0      MBR: a PReP boot partition (65, active) and a FreeBSD (165) one
byte 4096     PReP boot partition: boot1.elf, which SLOF loads and runs
1 MiB         UFS2 root, soft updates, label power_bsd: /boot/loader and the
              stock GENERIC64LE kernel, compressed (/boot/kernel/kernel.gz)
```

SLOF starts `boot1.elf`, which finds the UFS root and starts the loader,
which loads the kernel; `/boot/loader.conf` skips the menu. The userland is
the Sedna image's ([`board/common/userland.list`](board/common/userland.list),
without its network tools) plus `vi`, `tar` and what is described below
([`board/power-ppc64le/userland.list`](board/power-ppc64le/userland.list)),
with the same `initdisks` and so on
([`board/common/rootfs-overlay`](board/common/rootfs-overlay)). There is no
swap: the machine has 256 MB of memory or more. The kernel and the loader
take 12 MB; of the 63 MB root, ~17 MB are free.

Where the Sedna image has a small `/etc/rc` of its own, for a machine with
32 MB of memory, Power BSD boots with FreeBSD's init system, `rc(8)`:
`/etc/rc`, `rc.subr` and `/etc/defaults/rc.conf` from the release, with
`service(8)` and `sysrc(8)`. What the image starts is in its
[`/etc/rc.conf`](board/power-ppc64le/rootfs-overlay/etc/rc.conf); the host
name is `hostname=` there (`sysrc hostname=...`).

- **The scripts** in `/etc/rc.d` are not all 171 of the release, but the 38
  of what the image has programs for (`service -l`): file system checks and
  mounts, modules, `sysctl.conf`, the host name and id, `lo0` and what
  `rc.conf` sets up for other interfaces, `syslogd`, `cron`, `/etc/rc.local`
  and so on. `rc.conf` settings for a script that is not there do nothing;
  to add a daemon, list its program and its script in the board's
  `userland.list`.
- **The image's own** are [`mountdisks`](board/power-ppc64le/rootfs-overlay/etc/rc.d/mountdisks)
  (the other drives, see [More hard drives](#more-hard-drives)),
  [`oc2ports`](board/power-ppc64le/rootfs-overlay/etc/rc.d/oc2ports) and
  [`oc2busd`](board/power-ppc64le/rootfs-overlay/etc/rc.d/oc2busd) (the
  [device bus](#device-bus)); `mountdisks_enable` and `oc2busd_enable` turn
  two of them off.
- **Logs** are in memory, 1 MB of it (`/var/log` in `/etc/fstab`):
  `newsyslog(8)` makes them at boot and `cron(8)` has it turn them over
  every hour ([`newsyslog.conf`](board/power-ppc64le/rootfs-overlay/etc/newsyslog.conf),
  [`crontab`](board/power-ppc64le/rootfs-overlay/etc/crontab)). That is
  cron's only job.
- **File systems** are checked before they are mounted
  (`background_fsck="NO"`) and repaired if a plain check fails
  (`fsck_y_enable="YES"`), as the machine is often just switched off. If
  that fails too, `rc` stops and `init` asks for a shell on the console, as
  on any FreeBSD.
- **The first boot** writes a host id of its own to `/etc/hostid`, and
  `/boot/kernel/linker.hints`.

Booting takes about ten seconds longer for it, at OC2's speed with one
processor: 12 s from mounting the root file system to the login prompt,
where the small `/etc/rc` took 2.

The kernel has what the machine needs built in, so of the release's modules
the image has only one, `geom_stripe.ko`, for
[striped volumes](#striped-volumes-power-bsd), with `kldload`, `kldstat` and
`kldunload`. The loader cannot load modules on this machine (it turns them
down: "module 'kernel' exists but with wrong version"), so `rc.conf`'s
`kld_list` names those to load at boot, before the other drives are
mounted. To add a module, list `boot/kernel/<name>.ko` in the board's
`userland.list`, and the modules it depends on.

Two programs are built for the image, with the build host's clang against
the release's world: `mlapi(1)`, and `flua` (the image's `lua`), from the
release's own sources, its `src.txz`, with the patch the Sedna image's flua
has (0010): OC2's Lua libraries need its luaposix functions. The kernel is
the release's, so what the Sedna image gets from kernel patches is done
without here, see below.

```sh
make BOARD=power-ppc64le              # output/power-ppc64le/images/power-bsd.img
make datapack BOARD=power-ppc64le     # output/power-ppc64le/power-bsd-datapack.zip
make mod BOARD=power-ppc64le          # output/power-ppc64le/mod/power-bsd-MC1.21.1-*.jar
make test BOARD=power-ppc64le         # boot it on javapower, see Testing
```

In game, the drive is "Power BSD" (blue) in the creative tab. In survival,
craft it from a colossal hard drive, a PowerPC processor and blue dye
(shapeless; the processor is given back); the recipe only exists with an OC2
that has the PowerPC processor. Put it in a tier 5 computer with a PowerPC
processor and at least one 64 MB memory module, and connect a terminal to
the computer's serial port (9600 baud). `/root/getting_started.txt` shows
around. The power button cuts the power, as does saving the world (the
machine starts over when the world is loaded): shut down with `poweroff`
first; the file system is checked at boot either way.

The mod is "Power BSD" (`power_bsd`), separate from Sedna FreeBSD; both can
be installed together. Its version is the release and the image's build
number, e.g. `15.1-RELEASE-build-1` (the release sets have no patch level).

### Device bus

The tier 5 computer takes cards and connects to a device bus like the other
computers, and Power BSD uses both as the Sedna image does: the same
commands, the same Lua, see [High-level API](#high-level-api) and
[Mid-level API](#mid-level-api). What differs is where they are found, as
OC2's manual describes for PowerPC:

- **High-level API.** Its three ports are consoles of the machine, which the
  device tree labels `oc2.rpc.0`, `oc2.blob.0` and `oc2.event.0`. To FreeBSD
  every console is a `uart(4)`, `/dev/cuauN`, numbered in the order of the
  device tree (the console itself has the highest unit), so
  [`oc2ports`](board/power-ppc64le/rootfs-overlay/etc/rc.d/oc2ports) looks
  them up by label with `ofwdump(8)`, makes them raw and lists them in
  `/sys/class/virtio-ports`, where OC2's libraries look.
  [`oc2busd`](board/power-ppc64le/rootfs-overlay/etc/rc.d/oc2busd) then
  starts the bus daemon under `daemon(8)`: `service oc2busd status`, and
  `sysrc oc2busd_enable=NO` to do without it.
- **OC2's Lua libraries** are not on a `/mnt/builtin` here: the machine has
  no such file system, so the image brings them, unchanged, from OC2's
  sources: `devices`, `robot` and `oc2.*` in `/usr/share/flua` (flua's own
  path, so there is no `LUA_PATH` to set), and `lsdev.lua`, `redstone.lua`,
  `import.lua`, `export.lua` and `oc2busd.lua` in `/usr/bin`. The build takes
  them from an OC2 checkout next to this one, `OC2_SCRIPTS=` (default
  `../oc2/common/src/main/scripts`), and fails without it.
- **Mid-level API.** The registers are BAR 0 of a PCI device, `1234:4f43` in
  slot 31 (`pciconf -lv`: `none0@pci0:0:31:0`), which no driver takes.
  `mlapi(1)` and [`mlapi.h`](board/common/mlapi/mlapi.h) find it through
  `/dev/pci`: they switch on memory decoding, should the firmware have left
  it off, and map the BAR (`PCIOCBARMMAP`). Without a driver there is no
  interrupt, so `mlapi events` polls for events, every 10 ms.
- **Serial interface cards** are consoles too, labelled `oc2.serial.0` and
  up in slot order. `oc2ports` names them `/dev/ttyS1` and up, raw, and lists
  their unit addresses in `/sys/class/tty/ttyS*/iomem_base`, which is what
  the card's `getBaseAddress()` returns in this machine, so OC2's
  `oc2.serial` library finds its card. The line always runs at 9600 baud.
  The release's `stty` has no `-F`: use `stty -f`.
- **Network cards** are not set up yet. OC2 makes them virtual Ethernet
  devices, FreeBSD's `llan(4)`. `rc.conf` can set one up as on any FreeBSD
  (`ifconfig_llan0="inet ..."`, `defaultrouter`), but the image has no
  `dhclient` and no `ping`, and it was not tested with one.

With the release's kernel, a port can be opened by several programs at once
(the Sedna image's patch 0013 forbids it): while oc2busd runs, don't open the
ports directly. And the kernel takes all the host sends at once, before a
program can read any of it: 23 KB fit a port's input queue (the most a
terminal gets, at 115200 baud, which `oc2ports` sets for that), the rest is
dropped. A binary payload of 20 KB arrives intact, with 24 KB the call fails
with a timeout, and the bus carries on. OC2's devices stay far below that;
files are imported in 4 KB pieces.

## Building

Builds run on a FreeBSD 15.1 host (amd64 is fine); this machine only drives
them over ssh and gets the results back.

Tell the Makefile where to build, in `local.mk` (not tracked):

```make
BUILD_HOST = user@freebsd-host
# REMOTE_DIR = bsdbuildroot   # relative to the remote home, the default
```

```sh
make                    # copy the tree over, build everything, fetch output/images
make stages S=rootfs    # rerun single stages of scripts/build.sh
make shell              # ssh into the build directory there
```

`BOARD=` picks the image, `configs/<board>.conf`: `sedna-riscv64` (Sedna
FreeBSD, the default) or `power-ppc64le` ([Power BSD](#power-bsd), results in
`output/power-ppc64le`).

The tree is copied with tar over ssh, so the host needs nothing but ssh
access to start with. The first build clones the FreeBSD sources and takes a
few minutes on 12 cores (the host's clang is used, no cross compiler is
bootstrapped); later builds are incremental.

On the build host, the tools needed beyond the base system are git and
e2fsprogs (for the ext2 boot area; the root is made with base's `makefs`):

```sh
pkg install git e2fsprogs-core
```

(`scripts/build.sh` also looks in `~/.local/bin`, so e2fsprogs can be
unpacked there without root.)

### Configuration

Build settings are in [`configs/sedna-riscv64.conf`](configs/sedna-riscv64.conf)
(and [`configs/power-ppc64le.conf`](configs/power-ppc64le.conf) for Power
BSD): the image's name, the stages to run, the FreeBSD branch (or release
sets), kernel configuration, the userland lists and overlays, disk size and
layout, and:

- `TARGET_HOSTNAME`: the hostname, `localhost` by default.
- `TARGET_HOSTNAME_FROM_MAC`: `yes` gives the Linux image's behaviour: a
  machine still called `localhost` at boot is renamed after its network
  card's MAC address (`sedna-` and its last six hex digits), and the name is
  written to `/etc/hostname`, so it sticks, also if the card is changed
  later. That gives every computer a distinct name without setting one by
  hand. It only applies while `TARGET_HOSTNAME` is `localhost`. `no` (the
  default) keeps `TARGET_HOSTNAME`. The script it adds is
  [`board/sedna-riscv64/hostname-from-mac`](board/sedna-riscv64/hostname-from-mac).
- `TARGET_ISSUE`: the banner shown above the login prompt.
- `MLAPI_DIR`: the sources of `mlapi(1)`.
- `FLUA_PATCH`, `OC2_SCRIPTS_DIR`, `OC2_PROGRAMS` (Power BSD): the patch its
  flua is built with, and where OC2's Lua libraries and programs come from,
  see [Device bus](#device-bus).

Output in `output/images`:

| File                  | What                                                  |
|-----------------------|-------------------------------------------------------|
| `sedna-freebsd.img`   | the disk image (boot area, UFS root, swap); what OC2 boots |
| `build-number`        | the image's build number, counted up with every image; part of the mod's version |
| `Image`               | the kernel as sedna-fw loads it                       |
| `kernel`, `kernel.debug` | kernel ELF and its debug info, for gdb             |
| `firmware.bin`, `firmware.properties` | sedna-fw, if its sources are found (`SEDNA_FW=`, default `../buildroot/boot/sedna-fw/src`, a checkout of [fnuecke/buildroot](https://github.com/fnuecke/buildroot) next to this one). Only for testing; in game OC2's own copy is used |

### Layout

| Path | Linux tree equivalent |
|------|-----------------------|
| [`configs/sedna-riscv64.conf`](configs/sedna-riscv64.conf) | `configs/sedna-riscv64_defconfig` |
| [`board/sedna-riscv64/kernel/SEDNA`](board/sedna-riscv64/kernel/SEDNA) | `board/sedna-riscv64/linux.config` |
| [`board/common/userland.list`](board/common/userland.list), [`board/sedna-riscv64/userland.list`](board/sedna-riscv64/userland.list) | `board/sedna-riscv64/busybox.config` |
| [`board/sedna-riscv64/src.conf`](board/sedna-riscv64/src.conf) | (toolchain/world options) |
| [`board/sedna-riscv64/patches/freebsd`](board/sedna-riscv64/patches/freebsd) | `board/sedna-riscv64/patches/linux` |
| [`board/common/rootfs-overlay`](board/common/rootfs-overlay), [`board/sedna-riscv64/rootfs-overlay`](board/sedna-riscv64/rootfs-overlay) | `board/sedna-riscv64/rootfs-overlay` (the common one is shared with Power BSD) |
| [`board/sedna-riscv64/hostname-from-mac`](board/sedna-riscv64/hostname-from-mac) | its `etc/init.d/S01hostname`, optional here |
| [`board/common/mlapi`](board/common/mlapi) | (OC2's `include/mlapi.h`, and `mlapi(1)`; for both images) |
| [`rootfs-overlay/etc/rc.d/S91oc2`](board/sedna-riscv64/rootfs-overlay/etc/rc.d/S91oc2), Power BSD's [`oc2ports`](board/power-ppc64le/rootfs-overlay/etc/rc.d/oc2ports) and [`oc2busd`](board/power-ppc64le/rootfs-overlay/etc/rc.d/oc2busd) | (OC2's `init.d/S20export_lua_path` and `S25oc2busd`) |
| [`rootfs-overlay/etc/rc.d/S20dhclient`](board/sedna-riscv64/rootfs-overlay/etc/rc.d/S20dhclient) | (none: DHCP, off by default, see [Network](#network)) |
| [`rootfs-overlay/root/getting_started.txt`](board/sedna-riscv64/rootfs-overlay/root/getting_started.txt) | (none) |
| [`board/common/rootfs-overlay/usr/sbin/initdisks`](board/common/rootfs-overlay/usr/sbin/initdisks), [`mountdisks`](board/common/rootfs-overlay/usr/sbin/mountdisks) | (none: more hard drives, see [More hard drives](#more-hard-drives)) |
| [`board/common/rootfs-overlay/usr/share/flua/cjson.lua`](board/common/rootfs-overlay/usr/share/flua/cjson.lua) | (the `lua-cjson` package) |
| [`board/sedna-riscv64/post-build.sh`](board/sedna-riscv64/post-build.sh) | `board/sedna-riscv64/post-build.sh` |
| [`scripts/build.sh`](scripts/build.sh) | buildroot's `make` |
| [`board/sedna-riscv64/datapack`](board/sedna-riscv64/datapack) | (the OC2 data pack around the image) |
| [`mod`](mod) | (the data packs as Fabric and NeoForge mods: `mod/sedna-freebsd`, `mod/power-bsd`) |
| [`configs/power-ppc64le.conf`](configs/power-ppc64le.conf), [`board/power-ppc64le`](board/power-ppc64le) | (none: [Power BSD](#power-bsd), its userland additions, overlay, loader settings and data pack; OC2's Lua libraries come from OC2's sources) |

To add a program to the image, add its path to
[`userland.list`](board/sedna-riscv64/userland.list) (or the common list, for
both images); the shared libraries it
needs are found and added by the build (`readelf` on the ELF `NEEDED`
entries). Owners, modes and flags come from the METALOG of the world install
(`installworld -DNO_ROOT`, or made from the release sets for Power BSD), so
the build runs without root and the image is still owned by root. A program missing from the installed world may be
switched off in [`src.conf`](board/sedna-riscv64/src.conf).

## In game

```sh
make datapack                                    # output/sedna-freebsd-datapack.zip
make install-datapack MC_WORLD=".../saves/<world>"
```

Then `/reload` (or rejoin the world). The creative tab gets a hard drive
called "Sedna FreeBSD"; put it in a computer with the stock Sedna firmware
flash and 4 large memory sticks (32 MB). The console is the terminal.
`/root/getting_started.txt` (`less getting_started.txt` after logging in)
shows around.

In survival, craft it like OC2's own system drives: a hard drive, a RISC-V
CPU and red dye (shapeless; the CPU is given back, as with OC2's recipes).
The drive is the colossal one (64 MB), the smallest the 40 MB image fits;
with an OC2 that has no colossal drive, it is the large one instead (the
recipes in [`board/sedna-riscv64/datapack/data/sedna_freebsd/recipe`](board/sedna-riscv64/datapack/data/sedna_freebsd/recipe)
check which item exists, on NeoForge and on Fabric).

OC2 copies the image onto a drive the first time the drive is used, so after
updating the data pack take a fresh drive from the creative tab.

Made for OpenComputers II 0.11.0 on Minecraft 1.21.1 (NeoForge).

### As a mod

The same data pack also comes as a mod, for Fabric and NeoForge, which adds
it to every world, new and existing, so it needs no copying into saves:

```sh
make mod        # output/mod/sedna-freebsd-MC1.21.1-{fabric,neoforge}-<release>-build-<n>.jar
```

The mod's version is the FreeBSD release of the image it bundles and the
image's build number, e.g. `15.1-RELEASE-p6-build-3`: the release is read
from the kernel's version string in the image, the build number from
`output/images/build-number`, which the build counts up with every image (on
the build host, in `work/build-number`), so jars with different images never
share a version. Put
the jar for your loader in `mods/` next to OC2 (any OC2 version; on Fabric it
also needs Fabric API's resource loader, which OC2 requires anyway). The pack
shows up as "Sedna FreeBSD" in the world's data packs and can be turned off
with `/datapack disable`. On NeoForge, clients don't need the mod to join a
server that has it.

The Gradle project is [`mod`](mod), one project per mod (`sedna-freebsd`
and `power-bsd`), each with a Fabric and a
NeoForge project below it; their boards, images and output directories are in
[`mod/gradle.properties`](mod/gradle.properties). `./gradlew
:sedna-freebsd:dist` there builds the jars, with `-Psedna-freebsd.image=` for
another image, `-PbuildNumber=` to set the number; it builds on any JDK 21
or newer.

### More hard drives

`initdisks` sets up the computer's other hard drives: a UFS file system
(soft updates) with a volume label, mounted at `/mnt/<label>`.

```sh
initdisks                 # the disks and what is on them
initdisks -a              # every blank disk, as disk1, disk2, ...
initdisks vtbd1 games     # one disk, labelled "games": /mnt/games (da1 on Power BSD)
initdisks -f vtbd1        # also if it is not blank: erases it
```

At boot, [`mountdisks`](board/common/rootfs-overlay/usr/sbin/mountdisks)
checks and mounts every UFS file system with a label at `/mnt/<label>`, so
the drives come back after a restart, and a drive set up in another
computer is mounted too (`mountdisks` does it right away, `mountdisks -u`
unmounts them; `S10disks` runs it on the Sedna image, the `mountdisks`
service on Power BSD). Disks `/etc/fstab` lists
are left to it. The system disk is never touched. Give drives that move
between computers distinct labels: with two of the same, only one is
mounted.

#### Striped volumes (Power BSD)

Power BSD has `gstripe(8)`, to make one volume of several drives (RAID 0),
for instance of every drive but the system's:

```sh
gstripe label data da1 da2 da3    # the volume: /dev/stripe/data
newfs -U /dev/stripe/data         # no -L, see below
mountdisks                        # mounted at /mnt/data, as at every boot
```

`mountdisks` mounts every striped volume that has a file system at
`/mnt/<its name>`, and `initdisks` lists the drives as parts of the volume
and does not take them for blank ones.

A file system on a striped volume has its superblock on one of the drives,
where FreeBSD's label class finds it and offers that single drive as
`/dev/ufs/<label>`, without checking that the rest of the file system is
there. Checking and mounting that would damage the volume, so `mountdisks`
never mounts a labelled file system from a drive that carries gstripe's
mark (its last sector), on either image, and in a Sedna FreeBSD computer,
whose kernel has no stripe class, such drives are left alone. With a volume
label (`newfs -L`) everything still works, but the stripe class keeps
logging that it "cannot add disk ufs/<label>"; without one there is nothing
to see, as [`loader.conf`](board/power-ppc64le/rootfs-overlay/boot/loader.conf)
turns the label class's other names for a file system, `/dev/ufsid`, off.

### High-level API

OC2's [high-level API](https://fnuecke.github.io/oc2/manual/en_us/hlapi.html)
works as in the manual, with OC2's own Lua libraries from `/mnt/builtin`,
unchanged:

```sh
lsdev.lua                                   # devices and their type names
redstone.lua up 15                          # OC2's example script
lua -e 'd = require("devices"); r = d:find("redstone"); print(r)'
lua -e 'r = require("devices"):find("redstone"); r:setRedstoneOutput("up", 15)'
```

`S91oc2` sets it up at boot, after `/mnt/builtin` is mounted:

- The protocol's virtio ports (`oc2.rpc.0`, `oc2.blob.0`, `oc2.event.0`)
  are made raw (their `.init` devices, patch 0011) and listed in
  `/sys/class/virtio-ports`, where OC2's library looks them up as on Linux.
- `LUA_PATH` (`/etc/profile.d/lua_path.sh`) includes `/mnt/builtin/lib/lua`.
- `oc2busd`, which shares the ports between programs, runs under
  `daemon(8)`: restarted if it stops, output in `/var/log/messages`, and
  `protect(1)`ed, so that when memory runs out the kernel kills other
  programs rather than the bus every program depends on. Boot
  waits until it listens on `/run/oc2/bus`. `touch /etc/oc2busd.disabled`
  to not start it (Power BSD: `sysrc oc2busd_enable=NO`); then one program
  at a time opens the ports itself.

A port can only be open once at a time (patch 0013), as on Linux: while
oc2busd runs, a program that opens the ports itself (e.g. with
`OC2_BUS_SOCKET=none`) gets `EBUSY` instead of reading part of the daemon's
replies.

### Serial interface cards

The [serial interface card](https://fnuecke.github.io/oc2/manual/en_us/item/serial_interface_card.html)
is a 16550 UART, `uart(4)`. `S91oc2` names the cards as on Linux,
`/dev/ttyS1` for the first (a link to its `cuau` device, which does not
wait for a carrier; `ttyS0` would be the console), and lists their register
addresses in `/sys/class/tty/ttyS*/iomem_base`, where OC2's `oc2.serial`
library tells the cards apart. The manual's commands work, with `stty -F`
(patch 0014), whose settings stay until changed:

```sh
stty -F /dev/ttyS1 9600 raw -echo -crtscts
lua -e 'l = assert(require("oc2.serial").open(nil, 9600)); l:broadcast("hello")'
```

### Network

A [network interface card](https://fnuecke.github.io/oc2/manual/en_us/item/network_interface_card.html)
is `vtnet0`. Addresses are set by hand (`ifconfig vtnet0 inet 10.0.0.2/24`),
or by DHCP: `dhclient(8)` is in the image but off by default, since an OC2
network has no DHCP server unless another computer runs one.
`touch /etc/dhclient.enabled` turns it on from the next boot, for every
network card, or only those named in the file
([`S20dhclient`](board/sedna-riscv64/rootfs-overlay/etc/rc.d/S20dhclient)).
It goes to the background at once, so boot does not wait for a lease. The
image has no `resolvconf(8)`: `/etc/dhclient-enter-hooks` has
`dhclient-script(8)` write `/etc/resolv.conf` directly.

### Mid-level API

OC2's [mid-level API](https://github.com/fnuecke/oc2/blob/1.21.1/docs/mlapi.md#risc-v)
is `/dev/mlapi` (root only, as on Linux). The image has no C compiler, so
`mlapi(1)` wraps it for the shell and Lua (`os.execute`, `io.popen`):

```sh
mlapi list                    # index, device name, labels
mlapi find REDSTN door        # index of the first device with all these names
mlapi call door 3 1 15        # REDSTN setRedstoneOutput: side 1 (up), level 15
mlapi call REDSTN 1 1         # getRedstoneInput, side 1: prints the level
mlapi call -t SYSTEM 1 1 0    # getItemName of item 1, as text
mlapi call SYSTEM 2 s:stone   # s:TEXT passes text as argument bytes
mlapi events -n 1             # wait (interrupt, no polling) for an event:
                              # prints DEVICE CODE VALUE
```

Function codes and byte layouts are in OC2's device reference. Programs
written against OC2's `mlapi.h` (its `/mnt/builtin/example/*.c`) build
unchanged with [`board/common/mlapi/mlapi.h`](board/common/mlapi/mlapi.h),
the same library opening `/dev/mlapi`, or, on the tier 5 computer, the
[PCI device](#device-bus) that has the registers there. Cross compile them on
the build host like `mlapi(1)` is, see `stage_rootfs` in
[`scripts/build.sh`](scripts/build.sh).

## Testing

[`test/`](test) boots the images on Sedna itself, locally (needs a JDK; Sedna
is cloned and built on first use):

```sh
make test               # interactive: console on stdio
make -C test smoke      # scripted boot, a few commands, host-requested poweroff
```

The runner sets the board up like OC2: 4 × 8 MB memory, OC2's bootargs, and
`--oc2` adds OC2's built-in devices on OC2's interrupts (two Goldfish RTCs,
the 3-port RPC virtio console, a `builtin` 9p file system, whose contents
`--builtin DIR` sets), and an emulated mid-level API with a `SYSTEM` device
and a redstone interface (`REDSTN`, labelled `door`). In scripted runs, a
`--send "@redstone SIDE LEVEL"` changes the signal the redstone interface
receives, to test events. The RPC console answers the high-level API for the
same redstone interface (`redstone`, `door`) and a `test` device (`echo` returns
a binary payload, `fail` fails); `SEDNARUN_HLAPI_DEBUG=1` logs the messages.
`--serial` adds a serial interface card whose cable is looped back (what the
machine sends, it receives), and with `--oc2` its `serial` HLAPI device.
`--dhcp` adds a network card with a DHCP server at the other end, like a
home router's: it offers `10.0.2.15/24`, with router and name server
`10.0.2.2`, which also answers ping.
Point `--builtin` at OC2's scripts (`common/src/main/scripts` in its
sources; the files in `bin` need to be executable) to run its libraries.
Pass them with `ARGS="--oc2 --builtin DIR"`.
`FIRMWARE=` boots with another sedna-fw, e.g. the one in the OC2 jar, which is
what runs in game:

```sh
make -C test run ARGS=--oc2 FIRMWARE=$(make -s -C test oc2-firmware OC2_JAR=path/to/oc2.jar)
```

Power BSD boots on javapower, set up like the tier 5 computer: the
javapower release OC2 bundles (0.3.1, fetched from Maven Central on first
use), its SLOF, 256 MB, the image as a SCSI disk, run at OC2's pace
(200 million instructions a second, in slices per tick):

```sh
make test BOARD=power-ppc64le       # interactive: console on stdio
make -C test power-smoke            # scripted: log in, a few commands, poweroff
make -C test power-oc2-smoke        # the same with --oc2 --serial: the device
                                    # bus, through mlapi(1) and OC2's libraries
make -C test power-stripe-smoke     # three more drives as a striped volume,
                                    # booted twice: it is back after a restart
```

`ARGS=` passes options to the runner, [`test/PowerRun.java`](test/PowerRun.java):
`--cpus N`, `--ram MiB`, `--ips N` (0: full speed), `--baud 9600` to hold
the console to the serial port's speed, as in game, and the same
`--send`/`--wait-for`/`--timeout` as SednaRun. `--oc2` adds the device bus as
OC2 does: three consoles labelled `oc2.rpc.0`, `oc2.blob.0` and
`oc2.event.0` that answer the high-level API, and the mid-level API's PCI
device, both with the devices SednaRun emulates (the redstone interface,
`test`, and `@redstone SIDE LEVEL` to change its signal;
`POWERRUN_HLAPI_DEBUG=1` logs the messages). `--serial` adds a serial
interface card: a console labelled `oc2.serial.0`, looped back, and with
`--oc2` its `serial` device.

`--send "@sleep SEC"` in a script holds the next `--send` back for that
long.

### powerlinux

Linux for the tier 5 computer, powerlinux, is built by this tree's sibling,
[linuxbuildroot](https://github.com/xadaemon/linuxbuildroot): OC2's Sedna
Linux for ppc64le, with its own data pack and mod. It boots on the same
runner, of which that tree has a copy.

## License

BSD 2-Clause, see [LICENSE](LICENSE). The patches modify FreeBSD sources,
which keep their own licenses (mostly BSD 2-Clause, as is the Sedna system
controller driver patch 0003 adds).
