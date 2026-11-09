# Sedna FreeBSD

FreeBSD for the [Sedna](https://github.com/fnuecke/sedna) RISC-V virtual
machine (OpenComputers II), the FreeBSD counterpart of the Linux tree in
[fnuecke/buildroot](https://github.com/fnuecke/buildroot). Same machine, same, unmodified firmware: the
board firmware (sedna-fw, shipped in the OC2 jar) loads `/boot/Image` from an
ext2 file system on the first bootable virtio disk.

Based on FreeBSD 15.1 (`releng/15.1`).

The disk image is laid out so that sedna-fw finds an ext2 file system while
FreeBSD gets a UFS root (ext2 leaves its first 1024 bytes unused):

```
sector 0      MBR, two FreeBSD (165) partitions: the UFS root and the swap
byte 1024     small ext2 file system with only /boot/Image   (what sedna-fw reads)
next MiB      UFS2 root, soft updates, label sedna_freebsd   (what FreeBSD mounts)
last 8 MiB    swap, glabel label sedna_swap
```

## What is in the image

| Linux image (buildroot)            | FreeBSD image (this tree)                               |
|------------------------------------|---------------------------------------------------------|
| sedna-fw                           | the same sedna-fw, unchanged                            |
| Linux `Image`                      | FreeBSD kernel as a flat binary with a Linux boot image header (patch 0001) |
| ext2 root                          | UFS2 root with soft updates; `fsck -p` at boot          |
| busybox                            | ~90 regular FreeBSD programs with their shared libraries, picked from an installed world ([`userland.list`](board/sedna-riscv64/userland.list)) |
| busybox init + `/etc/init.d/rcS`   | `init(8)` + a small `/etc/rc` running `/etc/rc.d/S??*`  |
| getty + login, root without password | the same: getty + `login(1)` (PAM), root without password; `su`, `passwd`, `pw` work |
| busybox syslogd, `/var/log` in RAM | `syslogd(8)`, `/var/log` on tmpfs                       |
| hostname `sedna-xxxxxx` from the NIC's MAC (`S01hostname`) | `localhost`; the Linux behaviour with `TARGET_HOSTNAME_FROM_MAC="yes"`, see [Configuration](#configuration) |
| `S90virtio9p`, `S99mnt_init`       | same scripts, using `p9fs(4)`                           |
| Lua 5.4, luaposix, lua-cjson       | `lua` (FreeBSD's `flua`), its `posix` module extended with the luaposix functions OC2's libraries use (patch 0010), and a `cjson` module |
| OC2's `devices` library and `oc2busd` (high-level API) | the same, unchanged from `/mnt/builtin`, set up by `S91oc2`, see [High-level API](#high-level-api) |
| nano                               | `ee`                                                    |
| mid-level API as `/dev/uioN` (`uio_pdrv_genirq`), `mlapi.h` for tcc | `/dev/mlapi` (patch 0009) with the same interface; `mlapi(1)` to use it from the shell, `mlapi.h` to cross compile, see [Mid-level API](#mid-level-api) |

Kernel ([`board/sedna-riscv64/kernel/SEDNA`](board/sedna-riscv64/kernel/SEDNA)):
one hart, no modules, ns16550 console, virtio-mmio block/net/console/9p,
Goldfish RTC, PLIC, SBI timer, OC2's mid-level API, UFS root mounted by label, ext2fs, tmpfs, DDB.

It runs in OC2's largest configuration, 4 × 8 MB memory: about 25 MB usable
(`hw.physmem`) after the firmware's 2 MB, of which the kernel keeps ~9 MB;
~4 MB is free at the login prompt with oc2busd running. The disk is 32 MB:
5 MB boot area, 19 MB root with ~6 MB free, and 8 MB swap, so programs that
sit idle make room for others: about a dozen Lua programs using the
high-level API run at once next to oc2busd (5 without swap and the kernel
settings below).

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

Build settings are in [`configs/sedna-riscv64.conf`](configs/sedna-riscv64.conf):
the FreeBSD branch, kernel configuration, disk size and layout, and:

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

Output in `output/images`:

| File                  | What                                                  |
|-----------------------|-------------------------------------------------------|
| `sedna-freebsd.img`   | the disk image (boot area, UFS root, swap); what OC2 boots |
| `Image`               | the kernel as sedna-fw loads it                       |
| `kernel`, `kernel.debug` | kernel ELF and its debug info, for gdb             |
| `firmware.bin`, `firmware.properties` | sedna-fw, if its sources are found (`SEDNA_FW=`, default `../buildroot/boot/sedna-fw/src`, a checkout of [fnuecke/buildroot](https://github.com/fnuecke/buildroot) next to this one). Only for testing; in game OC2's own copy is used |

### Layout

| Path | Linux tree equivalent |
|------|-----------------------|
| [`configs/sedna-riscv64.conf`](configs/sedna-riscv64.conf) | `configs/sedna-riscv64_defconfig` |
| [`board/sedna-riscv64/kernel/SEDNA`](board/sedna-riscv64/kernel/SEDNA) | `board/sedna-riscv64/linux.config` |
| [`board/sedna-riscv64/userland.list`](board/sedna-riscv64/userland.list) | `board/sedna-riscv64/busybox.config` |
| [`board/sedna-riscv64/src.conf`](board/sedna-riscv64/src.conf) | (toolchain/world options) |
| [`board/sedna-riscv64/patches/freebsd`](board/sedna-riscv64/patches/freebsd) | `board/sedna-riscv64/patches/linux` |
| [`board/sedna-riscv64/rootfs-overlay`](board/sedna-riscv64/rootfs-overlay) | `board/sedna-riscv64/rootfs-overlay` |
| [`board/sedna-riscv64/hostname-from-mac`](board/sedna-riscv64/hostname-from-mac) | its `etc/init.d/S01hostname`, optional here |
| [`board/sedna-riscv64/mlapi`](board/sedna-riscv64/mlapi) | (OC2's `include/mlapi.h`, and `mlapi(1)`) |
| [`rootfs-overlay/etc/rc.d/S91oc2`](board/sedna-riscv64/rootfs-overlay/etc/rc.d/S91oc2) | (OC2's `init.d/S20export_lua_path` and `S25oc2busd`) |
| [`rootfs-overlay/usr/share/flua/cjson.lua`](board/sedna-riscv64/rootfs-overlay/usr/share/flua/cjson.lua) | (the `lua-cjson` package) |
| [`board/sedna-riscv64/post-build.sh`](board/sedna-riscv64/post-build.sh) | `board/sedna-riscv64/post-build.sh` |
| [`scripts/build.sh`](scripts/build.sh) | buildroot's `make` |
| [`datapack`](datapack) | (the OC2 data pack around the image) |

To add a program to the image, add its path to
[`userland.list`](board/sedna-riscv64/userland.list); the shared libraries it
needs are found and added by the build (`readelf` on the ELF `NEEDED`
entries). Owners, modes and flags come from the METALOG of the world install
(`installworld -DNO_ROOT`), so the build runs without root and the image is
still owned by root. A program missing from the installed world may be
switched off in [`src.conf`](board/sedna-riscv64/src.conf).

## In game

```sh
make datapack                                    # output/sedna-freebsd-datapack.zip
make install-datapack MC_WORLD=".../saves/<world>"
```

Then `/reload` (or rejoin the world). The creative tab gets a hard drive
called "Sedna FreeBSD"; put it in a computer with the stock Sedna firmware
flash and 4 large memory sticks (32 MB). The console is the terminal.

OC2 copies the image onto a drive the first time the drive is used, so after
updating the data pack take a fresh drive from the creative tab.

Made for OpenComputers II 0.11.0 on Minecraft 1.21.1 (NeoForge).

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
  to not start it; then one program at a time opens the ports itself.

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
unchanged with [`board/sedna-riscv64/mlapi/mlapi.h`](board/sedna-riscv64/mlapi/mlapi.h),
the same library opening `/dev/mlapi`. Cross compile them on the build host
like `mlapi(1)` is, see `stage_rootfs` in [`scripts/build.sh`](scripts/build.sh).

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
Point `--builtin` at OC2's scripts (`common/src/main/scripts` in its
sources; the files in `bin` need to be executable) to run its libraries.
Pass them with `ARGS="--oc2 --builtin DIR"`.
`FIRMWARE=` boots with another sedna-fw, e.g. the one in the OC2 jar, which is
what runs in game:

```sh
make -C test run ARGS=--oc2 FIRMWARE=$(make -s -C test oc2-firmware OC2_JAR=path/to/oc2.jar)
```

## License

BSD 2-Clause, see [LICENSE](LICENSE). The patches modify FreeBSD sources,
which keep their own licenses (mostly BSD 2-Clause, as is the Sedna system
controller driver patch 0003 adds).
