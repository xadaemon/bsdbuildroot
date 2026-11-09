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
sector 0      MBR, one FreeBSD (165) partition: the UFS root
byte 1024     small ext2 file system with only /boot/Image   (what sedna-fw reads)
next MiB      UFS2 root, soft updates, label sedna_freebsd   (what FreeBSD mounts)
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
| `S90virtio9p`, `S99mnt_init`       | same scripts, using `p9fs(4)`                           |
| Lua 5.4                            | `lua` (FreeBSD's `flua`, core library plus `posix`)     |
| nano                               | `ee`                                                    |

Kernel ([`board/sedna-riscv64/kernel/SEDNA`](board/sedna-riscv64/kernel/SEDNA)):
one hart, no modules, ns16550 console, virtio-mmio block/net/console/9p,
Goldfish RTC, PLIC, SBI timer, UFS root mounted by label, ext2fs, tmpfs, DDB.

It runs in OC2's largest configuration, 4 × 8 MB memory: about 25 MB usable
(`hw.physmem`) after the firmware's 2 MB and the kernel, ~6 MB of it free
at the login prompt. The disk is 24 MB: 5 MB boot area, 19 MB root with
~6 MB free.

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

The `src` stage commits the patches to a local `sedna` branch, with a fixed
author and dates, rather than leaving them as changes in the work tree. With
a clean tree the kernel's version string (`uname -a`) is reproducible: just
`FreeBSD 15.1-RELEASE-p6 sedna-<hash> SEDNA`, without the build user, host
and time that FreeBSD records for modified trees.

The kernel configuration also needs `options KTRACE`: without it, 15.1's
`subr_syscall.c` does not build.

### Not supported yet

- virtio-input (keyboard) and the framebuffer console: FreeBSD has no
  virtio-input driver, so only the serial console (OC2's terminal) works.
- virtio-sound: no FreeBSD driver.
- The firmware's `root=/dev/vdX` rewrite of the bootargs: FreeBSD finds its
  root file system by label (`/dev/ufs/sedna_freebsd`) instead, so don't
  attach two disks with this image at once.
- OC2's `/mnt/builtin/init.d` scripts are skipped (they are written for the
  Linux image, e.g. oc2busd); `touch /etc/mnt_init.builtin` runs them anyway.
  The 9p file system itself is mounted at `/mnt/builtin` as usual.
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

Output in `output/images`:

| File                  | What                                                  |
|-----------------------|-------------------------------------------------------|
| `sedna-freebsd.img`   | the disk image (boot area + UFS root); what OC2 boots |
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
`--builtin DIR` sets). Pass them with `ARGS="--oc2 --builtin DIR"`.
`FIRMWARE=` boots with another sedna-fw, e.g. the one in the OC2 jar, which is
what runs in game:

```sh
make -C test run ARGS=--oc2 FIRMWARE=$(make -s -C test oc2-firmware OC2_JAR=path/to/oc2.jar)
```

## License

BSD 2-Clause, see [LICENSE](LICENSE). The patches modify FreeBSD sources,
which keep their own licenses (mostly BSD 2-Clause, as is the Sedna system
controller driver patch 0003 adds).
