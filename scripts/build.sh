#!/bin/sh
#
# Builds the FreeBSD image for the Sedna RISC-V board. Runs on a FreeBSD
# host (amd64 is fine, everything is cross built); the top-level Makefile
# copies this tree there and runs it, see README.md.
#
# Usage: scripts/build.sh [stage ...]
#
# Stages, in the order "all" runs them:
#   src       clone/update the FreeBSD sources and apply the patches
#   world     buildworld
#   kernel    buildkernel, then the flat /boot/Image sedna-fw boots
#   install   installworld and distribution into a staging directory, as
#             an unprivileged user (-DNO_ROOT, ownership goes to a METALOG)
#   firmware  sedna-fw, if its sources are present
#   rootfs    pick the userland from the staging directory (USERLAND_LIST),
#             populate the root file system, make the disk image
#
# Environment: JOBS (default: hw.ncpu), CONFIG (default:
# configs/sedna-riscv64.conf).
#
set -eu

TOP=$(cd "$(dirname "$0")/.." && pwd)
cd "$TOP"

CONFIG=${CONFIG:-configs/sedna-riscv64.conf}
. "./$CONFIG"

JOBS=${JOBS:-$(sysctl -n hw.ncpu)}

WORK="$TOP/work"
SRC="$WORK/src"
OBJ="$WORK/obj"
OUT="$TOP/output"
TARGET_DIR="$OUT/target"
IMAGES="$OUT/images"
STAMPS="$WORK/stamps"

# Where buildworld/buildkernel put things, see share/mk/src.sys.obj.mk.
OBJTOP="$OBJ$SRC/$TARGET.$TARGET_ARCH"
KERNOBJ="$OBJTOP/sys/$KERNCONF"
# installworld + distribution, the full system the image is picked from.
WORLD="$WORK/world"

# e2fsprogs: a package, or unpacked to ~/.local, see README.md.
PATH="$PATH:$HOME/.local/bin:/usr/local/sbin"
export PATH

msg() {
	printf '>>> %s\n' "$*"
}

die() {
	printf 'error: %s\n' "$*" >&2
	exit 1
}

need() {
	for tool in "$@"; do
		command -v "$tool" >/dev/null 2>&1 ||
		    die "$tool not found; see README.md for the packages to install"
	done
}

srcmake() {
	env MAKEOBJDIRPREFIX="$OBJ" make -C "$SRC" -j"$JOBS" \
	    TARGET="$TARGET" TARGET_ARCH="$TARGET_ARCH" \
	    SRCCONF="$TOP/$SRCCONF" __MAKE_CONF=/dev/null "$@"
}

patches_hash() {
	cat "$PATCH_DIR"/*.patch 2>/dev/null | sha256 -q
}

stage_src() {
	need git
	mkdir -p "$WORK" "$STAMPS"
	if [ ! -d "$SRC/.git" ]; then
		msg "Cloning $FREEBSD_REPO ($FREEBSD_BRANCH)"
		git clone --depth 1 -b "$FREEBSD_BRANCH" "$FREEBSD_REPO" "$SRC"
	elif [ -n "${UPDATE_SRC:-}" ]; then
		msg "Updating sources ($FREEBSD_BRANCH)"
		git -C "$SRC" fetch --depth 1 origin "$FREEBSD_BRANCH"
	fi
	base=$(git -C "$SRC" rev-parse "origin/$FREEBSD_BRANCH")

	want="v2 $base $(patches_hash)"
	if [ "$(cat "$STAMPS/patches" 2>/dev/null)" = "$want" ]; then
		msg "Sources up to date, patches applied"
		return
	fi

	# The patches are committed to a local branch, "sedna", on top of the
	# upstream commit. A clean tree keeps the kernel's version string
	# reproducible: newvers.sh -R leaves out the build user, host and time
	# unless the tree has uncommitted changes. Author and dates are fixed,
	# so the commits, and the hash in the version, only depend on the
	# sources and the patches.
	msg "Applying patches from $PATCH_DIR to branch sedna"
	# Only files the patches touch change, so builds stay incremental.
	git -C "$SRC" checkout -q -f -B sedna "$base"
	git -C "$SRC" clean -q -fd -- sys tools
	date=$(git -C "$SRC" log -1 --format=%cI "$base")
	for patch in "$PATCH_DIR"/*.patch; do
		[ -f "$patch" ] || continue
		msg "  $(basename "$patch")"
		git -C "$SRC" apply --index --whitespace=nowarn "$TOP/$patch"
		# The commit message is the text before the diff.
		sed -n '/^diff --git/q;p' "$patch" > "$WORK/patch.msg"
		[ -s "$WORK/patch.msg" ] || basename "$patch" .patch > "$WORK/patch.msg"
		env GIT_AUTHOR_NAME="Sedna FreeBSD build" \
		    GIT_AUTHOR_EMAIL="build@sedna.invalid" GIT_AUTHOR_DATE="$date" \
		    GIT_COMMITTER_NAME="Sedna FreeBSD build" \
		    GIT_COMMITTER_EMAIL="build@sedna.invalid" \
		    GIT_COMMITTER_DATE="$date" \
		    git -C "$SRC" commit -q --no-verify -F "$WORK/patch.msg"
	done
	echo "$want" > "$STAMPS/patches"
}

stage_world() {
	msg "buildworld ($TARGET_ARCH, $JOBS jobs)"
	# Incremental after the first run: the world is only a means to an end.
	srcmake buildworld -DWITHOUT_CLEAN
}

stage_kernel() {
	need objcopy
	msg "buildkernel $KERNCONF"
	srcmake buildkernel KERNCONF="$KERNCONF" \
	    KERNCONFDIR="$TOP/$BOARD_DIR/kernel" NO_MODULES=yes \
	    -DNO_KERNELCLEAN

	mkdir -p "$IMAGES"
	# sedna-fw loads a flat binary with a Linux image header (patch 0001).
	objcopy -O binary "$KERNOBJ/kernel" "$IMAGES/Image"
	cp "$KERNOBJ/kernel" "$IMAGES/kernel"
	[ -f "$KERNOBJ/kernel.debug" ] && cp "$KERNOBJ/kernel.debug" "$IMAGES/"

	magic=$(od -An -tx1 -j56 -N4 "$IMAGES/Image" | tr -d ' ')
	[ "$magic" = "52534305" ] ||
	    die "Image has no RISC-V boot image header (magic2 $magic)"
	msg "Image: $(stat -f %z "$IMAGES/Image") bytes"
}

stage_install() {
	msg "installworld + distribution into $WORLD"
	rm -rf "$WORLD"
	mkdir -p "$WORLD"
	srcmake installworld DESTDIR="$WORLD" -DNO_ROOT
	srcmake -j1 distribution DESTDIR="$WORLD" -DNO_ROOT
	msg "world: $(du -shA "$WORLD" | awk '{ print $1 }')," \
	    "$(wc -l < "$WORLD/METALOG" | tr -d ' ') METALOG entries"
}

stage_firmware() {
	fwsrc="$TOP/$SEDNA_FW_SRC"
	if [ ! -f "$fwsrc/fw.ld" ]; then
		msg "No sedna-fw sources in $SEDNA_FW_SRC, skipping firmware"
		return
	fi
	need cc objcopy nm
	msg "Building sedna-fw"
	fwobj="$WORK/sedna-fw"
	rm -rf "$fwobj"
	mkdir -p "$fwobj" "$IMAGES"
	# The same flags as the firmware's Makefile, with the host's clang.
	cflags="--target=riscv64-unknown-elf -Os -Wall -Wextra -ffreestanding \
	    -nostdlib -fno-stack-protector -fno-pic -mcmodel=medany -mno-relax \
	    -march=rv64imac_zicsr_zifencei -mabi=lp64"
	objs=
	for src in start.S fw.c fdt.c virtio.c ext2.c uart.c sbi.c lib.c; do
		o="$fwobj/${src%.*}.o"
		cc $cflags -c -o "$o" "$fwsrc/$src"
		objs="$objs $o"
	done
	cc $cflags -fuse-ld=lld -static -Wl,--build-id=none \
	    -T "$fwsrc/fw.ld" -o "$fwobj/firmware.elf" $objs
	objcopy -O binary "$fwobj/firmware.elf" "$IMAGES/firmware.bin"
	size=$(nm "$fwobj/firmware.elf" | awk '$3 == "_fw_region_size" { print $1 }')
	printf 'regionSize=%d\n' "0x$size" > "$IMAGES/firmware.properties"
	msg "firmware.bin: $(stat -f %z "$IMAGES/firmware.bin") bytes"
}

# Prints the shared libraries the ELF files given as arguments (paths in
# $WORLD) need, and the libraries those need, as paths relative to $WORLD.
library_closure() {
	seen=" "
	queue="$*"
	while [ -n "$queue" ]; do
		needed=$(for f in $queue; do
			readelf -d "$WORLD/$f" 2>/dev/null |
			    awk '/\(NEEDED\)/ { gsub(/[][]/, "", $NF); print $NF }'
		done | sort -u)
		queue=
		for lib in $needed; do
			path=
			for dir in lib usr/lib; do
				if [ -e "$WORLD/$dir/$lib" ]; then
					path="$dir/$lib"
					break
				fi
			done
			[ -n "$path" ] || die "no library $lib in the world"
			case "$seen" in *" $path "*) continue ;; esac
			seen="$seen$path "
			queue="$queue $path"
			echo "$path"
		done
	done
}

stage_rootfs() {
	need mke2fs e2fsck makefs fsck_ffs readelf cap_mkdb
	[ -f "$IMAGES/Image" ] || die "no kernel, run the kernel stage first"
	[ -f "$WORLD/METALOG" ] || die "no installed world, run the install stage first"

	msg "Picking the userland from $USERLAND_LIST"
	# Expand the list (globs) against the world.
	files="$WORK/userland.files"
	(cd "$WORLD" && sed -e 's/#.*//' -e '/^[[:space:]]*$/d' \
	    "$TOP/$USERLAND_LIST" | while read -r pattern; do
		set -- $pattern
		for f in "$@"; do
			[ -e "$f" ] || [ -L "$f" ] ||
			    die "$pattern: not in the installed world"
			echo "$f"
		done
	done) > "$files"
	# Plus every library they need, and the run-time linker.
	elves=$(cd "$WORLD" && while read -r f; do
		if [ -d "$f" ]; then find "$f" -type f; else echo "$f"; fi
	done < "$files")
	library_closure $elves > "$WORK/userland.libs"
	echo libexec/ld-elf.so.1 >> "$WORK/userland.libs"
	cat "$WORK/userland.libs" >> "$files"
	msg "  $(wc -l < "$files" | tr -d ' ') paths," \
	    "$(wc -l < "$WORK/userland.libs" | tr -d ' ') of them libraries"

	msg "Populating $TARGET_DIR"
	rm -rf "$TARGET_DIR"
	mkdir -p "$TARGET_DIR"
	t="$TARGET_DIR"
	# tar keeps hard links between the files it copies (test and [, ...).
	(cd "$WORLD" && tar -cf - -T "$files") | (cd "$t" && tar -xpf -)
	for d in boot dev etc/rc.d etc/profile.d mnt root tmp usr/share/misc \
	    var/db var/empty var/log var/run var/tmp; do
		mkdir -p "$t/$d"
	done

	# Only the terminal types a Sedna console is likely to be.
	awk -v want="dumb vt100 vt102 vt220 ansi xterm xterm-256color screen screen-256color" \
	    -f "$TOP/scripts/termcap-extract.awk" "$WORLD/usr/share/misc/termcap" \
	    > "$t/usr/share/misc/termcap"
	cap_mkdb -f "$t/usr/share/misc/termcap" "$t/usr/share/misc/termcap"

	msg "Applying overlay $ROOTFS_OVERLAY"
	cp -R "$TOP/$ROOTFS_OVERLAY/." "$t/"
	# Overlay files get their own modes; its directories keep the world's.
	(cd "$TOP/$ROOTFS_OVERLAY" && find . -mindepth 1 ! -type d) \
	    > "$WORK/overlay.files"

	printf '%s\n' "$TARGET_HOSTNAME" > "$t/etc/hostname"
	case "$TARGET_HOSTNAME_FROM_MAC" in
	yes)
		msg "Hostname from the NIC's MAC (TARGET_HOSTNAME_FROM_MAC)"
		cp -R "$TOP/$BOARD_DIR/hostname-from-mac/." "$t/"
		;;
	no) ;;
	*) die "TARGET_HOSTNAME_FROM_MAC must be yes or no" ;;
	esac
	printf '%s\n' "$TARGET_ISSUE" > "$t/etc/issue"

	# mlapi(1), OC2's mid-level API from the shell, against the world just
	# installed. The host's clang cross compiles.
	msg "Building mlapi(1)"
	need cc
	release=$(awk -F'"' '/^REVISION=/ { print $2 }' "$SRC/sys/conf/newvers.sh")
	cc --target="$TARGET_ARCH-unknown-freebsd$release" --sysroot="$WORLD" \
	    -fuse-ld=lld -O2 -Wall -Wextra -Werror -s \
	    -o "$t/usr/bin/mlapi" "$TOP/$BOARD_DIR/mlapi/mlapi.c"

	if [ -n "$ROOTFS_POST_BUILD_SCRIPT" ]; then
		msg "Running $ROOTFS_POST_BUILD_SCRIPT"
		sh "$TOP/$ROOTFS_POST_BUILD_SCRIPT" "$t"
	fi

	make_manifest
	make_disk
}

# The mtree manifest makefs builds / from: everything in the target, with
# owners, modes and flags from the world's METALOG where the file comes from
# the world, and root:wheel with the file's own mode otherwise (the overlay,
# files made here).
make_manifest() {
	(cd "$TARGET_DIR" && find . | sort | while read -r path; do
		if [ -L "$path" ]; then
			printf '%s type=link mode=0755 link=%s\n' \
			    "$path" "$(readlink "$path")"
		elif [ -d "$path" ]; then
			printf '%s type=dir mode=%s\n' "$path" "$(stat -f %Mp%Lp "$path")"
		else
			printf '%s type=file mode=%s\n' "$path" "$(stat -f %Mp%Lp "$path")"
		fi
	done) > "$WORK/target.mtree"
	awk -v overlay="$WORK/overlay.files" '
	BEGIN {
		while ((getline line < overlay) > 0)
			ours[line] = 1
	}
	# The METALOG: the last entry for a path wins. Keep what describes the
	# file, not its contents (the overlay and post-build may change them).
	FILENAME != "-" && FILENAME ~ /METALOG$/ {
		entry = $1
		for (i = 2; i <= NF; i++)
			if ($i ~ /^(type|uname|gname|mode|link|flags)=/)
				entry = entry " " $i
		meta[$1] = entry
		next
	}
	{
		if (($1 in meta) && !($1 in ours))
			print meta[$1]
		else
			print $0 " uname=root gname=wheel"
	}' "$WORLD/METALOG" - < "$WORK/target.mtree" > "$WORK/rootfs.mtree"
}

# Converts sizes like 16M or 512K to bytes.
to_bytes() {
	case "$1" in
	*[Gg]) echo $(( ${1%?} * 1024 * 1024 * 1024 )) ;;
	*[Mm]) echo $(( ${1%?} * 1024 * 1024 )) ;;
	*[Kk]) echo $(( ${1%?} * 1024 )) ;;
	*) echo "$1" ;;
	esac
}

# Prints a 32 bit little endian value as printf(1) octal escapes.
le32() {
	printf '\\%03o\\%03o\\%03o\\%03o' $(( $1 & 255 )) $(( ($1 >> 8) & 255 )) \
	    $(( ($1 >> 16) & 255 )) $(( ($1 >> 24) & 255 ))
}

# The disk: MBR, ext2 boot area with /boot/Image for sedna-fw, UFS root.
# See DISK_SIZE in the configuration for the layout.
make_disk() {
	mib=$((1024 * 1024))
	disk_bytes=$(to_bytes "$DISK_SIZE")
	image_bytes=$(stat -f %z "$IMAGES/Image")
	if [ -n "$BOOTFS_SIZE" ]; then
		boot_bytes=$((BOOTFS_SIZE * mib))
	else
		# The kernel and ext2 metadata, rounded up to MiB. The image is
		# rebuilt with every kernel, so no room for a larger kernel.
		boot_bytes=$(( (image_bytes + mib / 2 + mib - 1) / mib * mib ))
	fi
	root_bytes=$((disk_bytes - boot_bytes))
	[ "$root_bytes" -gt 0 ] || die "DISK_SIZE $DISK_SIZE leaves no room for /"

	img="$IMAGES/sedna-freebsd.img"
	bootdir="$WORK/bootfs"
	used=$(du -skA "$TARGET_DIR" | awk '{ print $1 }')
	msg "Making $img: $DISK_SIZE, boot $((boot_bytes / 1024))K," \
	    "root $((root_bytes / 1024))K (${used}K used)"

	# The boot area. As buildroot's fs/ext2, which is what sedna-fw reads:
	# revision 1, ext2, no 64bit, 128 byte inodes. Ownership does not matter,
	# FreeBSD does not use it.
	rm -rf "$bootdir" "$WORK/boot.ext2"
	mkdir -p "$bootdir/boot"
	install -m 0644 "$IMAGES/Image" "$bootdir/boot/Image"
	mke2fs -q -F -t ext2 -r 1 -b 1024 -I 128 -m 0 -O ^64bit \
	    -L "$BOOTFS_LABEL" -d "$bootdir" "$WORK/boot.ext2" \
	    "$((boot_bytes / 1024))k"
	e2fsck -fn "$WORK/boot.ext2" >/dev/null ||
	    die "e2fsck found problems in the boot area"

	# The root file system, from the manifest (see make_manifest), so it is
	# owned by root no matter who builds it.
	rm -f "$WORK/root.ufs"
	(cd "$TARGET_DIR" && makefs -t ffs -s "$root_bytes" \
	    -o "label=$ROOTFS_LABEL,$ROOTFS_MAKEFS_OPTIONS" -N "$TARGET_DIR/etc" \
	    "$WORK/root.ufs" "$WORK/rootfs.mtree") > "$WORK/makefs.log" 2>&1 ||
	    { cat "$WORK/makefs.log" >&2; die "makefs failed"; }
	fsck_ffs -fn "$WORK/root.ufs" > "$WORK/fsck.log" 2>&1 ||
	    { cat "$WORK/fsck.log" >&2; die "fsck_ffs found problems in /"; }

	cat "$WORK/boot.ext2" "$WORK/root.ufs" > "$img.tmp"

	# MBR, written last: mke2fs clears the first sector. One FreeBSD (165)
	# partition, the root file system; the boot area before it is unused
	# space as far as partitioning goes.
	entry="\\000\\376\\377\\377\\245\\376\\377\\377$(le32 $((boot_bytes / 512)))$(le32 $((root_bytes / 512)))"
	printf "$entry" | dd of="$img.tmp" bs=1 seek=446 conv=notrunc 2>/dev/null
	printf '\125\252' | dd of="$img.tmp" bs=1 seek=510 conv=notrunc 2>/dev/null
	magic=$(od -An -tx1 -j1080 -N2 "$img.tmp" | tr -d ' ')
	[ "$magic" = "53ef" ] || die "the MBR clobbered the ext2 superblock"
	mv "$img.tmp" "$img"
	msg "$(basename "$img") done"
}

stages=${*:-all}
[ "$stages" = all ] && stages="src world kernel install firmware rootfs"
for s in $stages; do
	case "$s" in
	src|world|kernel|install|firmware|rootfs) "stage_$s" ;;
	*) die "unknown stage $s" ;;
	esac
done
