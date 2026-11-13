#!/bin/sh
#
# Builds a FreeBSD image for an OpenComputers II machine, as set up by a
# configuration in configs/. Runs on a FreeBSD host (amd64 is fine,
# everything is cross built or fetched); the top-level Makefile copies this
# tree there and runs it, see README.md.
#
# Usage: scripts/build.sh [stage ...]
#
# Stages; "all" runs those the configuration lists in STAGES, in this order:
#   src       clone/update the FreeBSD sources and apply the patches
#   world     buildworld
#   kernel    buildkernel, then the flat /boot/Image sedna-fw boots
#   install   installworld and distribution into a staging directory, as
#             an unprivileged user (-DNO_ROOT, ownership goes to a METALOG)
#   fetch     instead of the four above: the release's distribution sets
#             (DIST_SETS from DIST_SITE), unpacked into the staging directory
#             with a METALOG made from them
#   flua      with fetch: flua (the image's lua) from the release's sources,
#             its src set, with FLUA_PATCH applied
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

WORK="$TOP/$WORK_DIR"
SRC="$WORK/src"
OBJ="$WORK/obj"
OUT="$TOP/$OUTPUT_DIR"
TARGET_DIR="$OUT/target"
IMAGES="$OUT/images"
STAMPS="$WORK/stamps"

# Where buildworld/buildkernel put things, see share/mk/src.sys.obj.mk.
OBJTOP="$OBJ$SRC/$TARGET.$TARGET_ARCH"
KERNOBJ="$OBJTOP/sys/${KERNCONF:-}"
# installworld + distribution (or the release sets), the full system the
# image is picked from.
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

# The FreeBSD version of the installed world, e.g. 15.1, for the target
# triple of what is cross compiled against it with the host's clang.
world_release() {
	sed -n 's/^USERLAND_VERSION="\([0-9.]*\).*/\1/p' \
	    "$WORLD/bin/freebsd-version"
}

# Fetches the release's MANIFEST, which lists its distribution sets with
# their checksums.
fetch_manifest() {
	need fetch sha256 tar
	dist="$WORK/dist"
	mkdir -p "$dist" "$STAMPS"
	msg "Fetching $DIST_SITE/MANIFEST"
	fetch -q -o "$dist/MANIFEST.new" "$DIST_SITE/MANIFEST" ||
	    die "could not fetch $DIST_SITE/MANIFEST"
	mv "$dist/MANIFEST.new" "$dist/MANIFEST"
}

# Fetches distribution set $1 to $dist/$1.txz, unless it is there already,
# and checks it against the MANIFEST.
fetch_set() {
	sum=$(awk -v f="$1.txz" '$1 == f { print $2 }' "$dist/MANIFEST")
	[ -n "$sum" ] || die "$1.txz is not in $DIST_SITE/MANIFEST"
	if [ "$(sha256 -q "$dist/$1.txz" 2>/dev/null)" != "$sum" ]; then
		msg "Fetching $1.txz"
		fetch -q -o "$dist/$1.txz" "$DIST_SITE/$1.txz" ||
		    die "could not fetch $DIST_SITE/$1.txz"
		[ "$(sha256 -q "$dist/$1.txz")" = "$sum" ] ||
		    die "$1.txz does not match the MANIFEST's checksum"
	fi
}

# The release's distribution sets, unmodified, in place of a built world.
stage_fetch() {
	fetch_manifest
	archives=
	for set in $DIST_SETS; do
		fetch_set "$set"
		archives="$archives @$dist/$set.txz"
	done

	want="$(sha256 -q "$dist/MANIFEST") $DIST_SETS"
	if [ -f "$WORLD/METALOG" ] &&
	    [ "$(cat "$STAMPS/fetch" 2>/dev/null)" = "$want" ]; then
		msg "Distribution sets up to date in $WORLD"
		return
	fi
	msg "Unpacking $DIST_SETS into $WORLD"
	# Unpacked by an unprivileged user, some directories are read-only.
	[ ! -d "$WORLD" ] || chmod -R u+w "$WORLD"
	rm -rf "$WORLD"
	mkdir -p "$WORLD"
	for set in $DIST_SETS; do
		tar -xf "$dist/$set.txz" -C "$WORLD"
	done
	# Ownership, modes and flags, as installworld -DNO_ROOT records them.
	# shellcheck disable=SC2086
	tar -cf - --format=mtree \
	    --options='!all,type,uname,gname,mode,flags,link' $archives \
	    > "$WORLD/METALOG"
	echo "$want" > "$STAMPS/fetch"
	msg "world: $(du -shA "$WORLD" | awk '{ print $1 }')," \
	    "$(wc -l < "$WORLD/METALOG" | tr -d ' ') METALOG entries"
}

# flua, the image's lua, with FLUA_PATCH: the luaposix functions OC2's Lua
# libraries need, which the release's flua lacks. Built from the release's
# own sources (its src set, of which only flua's part is unpacked) as its
# Makefiles build it (libexec/flua, lib/liblua), against the release's world,
# with the host's clang. The modules in /usr/lib/flua are the release's.
stage_flua() {
	need cc patch
	[ -f "$WORLD/METALOG" ] || die "no world, run the fetch stage first"
	fetch_manifest
	fetch_set src
	flua="$WORK/flua"
	want="v1 $(sha256 -q "$dist/src.txz") $(sha256 -q "$TOP/$FLUA_PATCH")"
	if [ -x "$flua/flua" ] &&
	    [ "$(cat "$STAMPS/flua" 2>/dev/null)" = "$want" ]; then
		msg "flua up to date"
		return
	fi

	msg "Building flua with $(basename "$FLUA_PATCH")"
	rm -rf "$flua"
	mkdir -p "$flua"
	tar -xf "$dist/src.txz" -C "$flua" --strip-components 2 \
	    usr/src/contrib/lua/src usr/src/lib/liblua usr/src/libexec/flua
	(cd "$flua" && patch -s -p1 < "$TOP/$FLUA_PATCH") ||
	    die "$FLUA_PATCH does not apply to the release's sources"
	# liblua, without the linit.c flua replaces and the compiler's main().
	srcs=$(ls "$flua"/contrib/lua/src/*.c | grep -v -e '/linit\.c$' \
	    -e '/luac\.c$')
	# shellcheck disable=SC2086
	cc --target="$TARGET_ARCH-unknown-freebsd$(world_release)" \
	    --sysroot="$WORLD" -fuse-ld=lld -O2 -pipe -fPIE -pie \
	    -fstack-protector-strong -w -s \
	    -I"$flua/lib/liblua" -I"$flua/libexec/flua/modules" \
	    -I"$flua/contrib/lua/src" -I"$flua/libexec/flua" \
	    -DLUA_PROGNAME='"flua"' \
	    -DLUA_USE_READLINE -I"$WORLD/usr/include/edit" \
	    -o "$flua/flua" $srcs "$flua/libexec/flua/linit_flua.c" \
	    "$flua/libexec/flua/modules/lposix.c" -Wl,-E -ledit -lm
	echo "$want" > "$STAMPS/flua"
	msg "flua: $(stat -f %z "$flua/flua") bytes"
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
	need makefs fsck_ffs readelf cap_mkdb
	case "$DISK_LAYOUT" in
	sedna)
		need mke2fs e2fsck
		[ -f "$IMAGES/Image" ] ||
		    die "no kernel, run the kernel stage first"
		;;
	prep) ;;
	*) die "DISK_LAYOUT must be sedna or prep" ;;
	esac
	[ -f "$WORLD/METALOG" ] ||
	    die "no installed world, run the install (or fetch) stage first"

	msg "Picking the userland from $USERLAND_LIST"
	# Expand the lists (globs) against the world.
	files="$WORK/userland.files"
	lists=
	for list in $USERLAND_LIST; do
		lists="$lists $TOP/$list"
	done
	# shellcheck disable=SC2086
	(cd "$WORLD" && sed -e 's/#.*//' -e '/^[[:space:]]*$/d' \
	    $lists | while read -r pattern; do
		set -- $pattern
		for f in "$@"; do
			[ -e "$f" ] || [ -L "$f" ] ||
			    die "$pattern: not in the installed world"
			echo "$f"
		done
	done) > "$files"
	# Plus every library they need, and the run-time linker. Not for /boot:
	# the loader is static, and the kernel's NEEDED entries are not
	# libraries (powerpc's names a dummy, force-dynamic-hack.pico).
	elves=$(cd "$WORLD" && grep -v '^boot/' "$files" | while read -r f; do
		if [ -d "$f" ]; then find "$f" -type f; else echo "$f"; fi
	done)
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
	for d in boot dev etc/rc.d etc/profile.d mnt root run sys tmp \
	    usr/share/misc var/db var/empty var/log var/run var/tmp; do
		mkdir -p "$t/$d"
	done

	# Only the terminal types a Sedna console is likely to be.
	awk -v want="dumb vt100 vt102 vt220 ansi xterm xterm-256color screen screen-256color" \
	    -f "$TOP/scripts/termcap-extract.awk" "$WORLD/usr/share/misc/termcap" \
	    > "$t/usr/share/misc/termcap"
	cap_mkdb -f "$t/usr/share/misc/termcap" "$t/usr/share/misc/termcap"

	# Overlay files get their own modes; its directories keep the world's.
	: > "$WORK/overlay.files"
	for overlay in $ROOTFS_OVERLAY; do
		msg "Applying overlay $overlay"
		cp -R "$TOP/$overlay/." "$t/"
		(cd "$TOP/$overlay" && find . -mindepth 1 ! -type d) \
		    >> "$WORK/overlay.files"
	done

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
	if [ -n "$SWAP_SIZE" ]; then
		printf '/dev/label/%s\t\tnone\t\tswap\tsw\t\t0\t0\n' \
		    "$SWAP_LABEL" >> "$t/etc/fstab"
	fi

	# mlapi(1), OC2's mid-level API from the shell, against the world just
	# installed. The host's clang cross compiles.
	if [ -n "${MLAPI_DIR:-}" ]; then
		msg "Building mlapi(1)"
		need cc
		cc --target="$TARGET_ARCH-unknown-freebsd$(world_release)" \
		    --sysroot="$WORLD" -fuse-ld=lld -O2 -Wall -Wextra -Werror -s \
		    -o "$t/usr/bin/mlapi" "$TOP/$MLAPI_DIR/mlapi.c"
	fi

	# The flua of the flua stage, in place of the world's.
	if [ -n "${FLUA_PATCH:-}" ]; then
		[ -x "$WORK/flua/flua" ] || die "no flua, run the flua stage first"
		msg "Installing the patched flua"
		rm -f "$t/usr/libexec/flua"
		install -m 0555 "$WORK/flua/flua" "$t/usr/libexec/flua"
	fi

	# OC2's Lua libraries for its high-level API, and the programs that
	# come with them, from OC2's sources: where the machine has no builtin
	# file system to bring them, as the RISC-V computers' /mnt/builtin does.
	if [ -n "${OC2_SCRIPTS_DIR:-}" ]; then
		scripts="$TOP/$OC2_SCRIPTS_DIR"
		[ -f "$scripts/lib/lua/devices.lua" ] ||
		    die "no OC2 scripts in $OC2_SCRIPTS_DIR: they are" \
		        "common/src/main/scripts of OC2's sources, see README.md"
		msg "Installing OC2's Lua libraries from $OC2_SCRIPTS_DIR"
		mkdir -p "$t/usr/share/flua"
		cp -R "$scripts/lib/lua/." "$t/usr/share/flua/"
		find "$t/usr/share/flua" -type f -exec chmod 0644 {} +
		for program in $OC2_PROGRAMS; do
			[ -f "$scripts/bin/$program" ] ||
			    die "no bin/$program in $OC2_SCRIPTS_DIR"
			install -m 0755 "$scripts/bin/$program" "$t/usr/bin/$program"
		done
	fi

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
		for (i = 2; i <= NF; i++) {
			if ($i ~ /^(type|uname|gname|mode|link|flags)=/)
				entry = entry " " $i
			if ($i ~ /^type=/)
				kind[$1] = $i
		}
		meta[$1] = entry
		next
	}
	# Not where the target has something else than the world: /sys is a
	# directory here, a link to the kernel sources in a release.
	{
		if (($1 in meta) && !($1 in ours) && kind[$1] == $2)
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

# Writes MBR partition table entry INDEX (0-3) of $img.tmp: at byte OFFSET,
# SIZE bytes, of TYPE (default 165, FreeBSD), active if the fifth argument
# is "active". The CHS addresses are left at their "use the LBA" maximum.
mbr_entry() {
	status='\000'
	[ "${5:-}" = active ] && status='\200'
	type=$(printf '\\%03o' "${4:-165}")
	printf "$status\\376\\377\\377$type\\376\\377\\377$(le32 $(($2 / 512)))$(le32 $(($3 / 512)))" |
	    dd of="$img.tmp" bs=1 seek=$((446 + $1 * 16)) conv=notrunc 2>/dev/null
}

# The UFS2 root file system, $WORK/root.ufs, ROOT_BYTES bytes, from the
# manifest (see make_manifest), so it is owned by root no matter who builds
# it.
make_rootfs() {
	rm -f "$WORK/root.ufs"
	(cd "$TARGET_DIR" && makefs -t ffs -B little -s "$1" \
	    -o "label=$ROOTFS_LABEL,$ROOTFS_MAKEFS_OPTIONS" -N "$TARGET_DIR/etc" \
	    "$WORK/root.ufs" "$WORK/rootfs.mtree") > "$WORK/makefs.log" 2>&1 ||
	    { cat "$WORK/makefs.log" >&2; die "makefs failed"; }
	fsck_ffs -fn "$WORK/root.ufs" > "$WORK/fsck.log" 2>&1 ||
	    { cat "$WORK/fsck.log" >&2; die "fsck_ffs found problems in /"; }
}

# Makes the last SWAP_BYTES of $img.tmp (DISK_BYTES large) MBR partition
# INDEX, for swap: a glabel(8) label in its last sector, so fstab finds it as
# /dev/label/<name> whatever the disk is called.
make_swap() {
	truncate -s "$disk_bytes" "$img.tmp"
	mbr_entry "$1" $((disk_bytes - swap_bytes)) "$swap_bytes"
	# struct g_label_metadata: magic, version 2, name, provider size.
	{
		printf 'GEOM::LABEL\000\000\000\000\000'
		printf "$(le32 2)"
		printf '%s' "$SWAP_LABEL"
		head -c $((16 - ${#SWAP_LABEL})) /dev/zero
		printf "$(le32 $((swap_bytes & 0xffffffff)))$(le32 $((swap_bytes >> 32)))"
	} | dd of="$img.tmp" bs=512 seek=$((disk_bytes / 512 - 1)) \
	    conv=notrunc 2>/dev/null
}

# Prints a 32 bit little endian value as printf(1) octal escapes.
le32() {
	printf '\\%03o\\%03o\\%03o\\%03o' $(( $1 & 255 )) $(( ($1 >> 8) & 255 )) \
	    $(( ($1 >> 16) & 255 )) $(( ($1 >> 24) & 255 ))
}

# The disk image, laid out as DISK_LAYOUT says, then numbered.
make_disk() {
	mib=$((1024 * 1024))
	disk_bytes=$(to_bytes "$DISK_SIZE")
	swap_bytes=0
	[ -z "$SWAP_SIZE" ] || swap_bytes=$(to_bytes "$SWAP_SIZE")
	img="$IMAGES/$NAME.img"
	used=$(du -skA "$TARGET_DIR" | awk '{ print $1 }')
	mkdir -p "$IMAGES"

	"make_disk_$DISK_LAYOUT"

	printf '\125\252' | dd of="$img.tmp" bs=1 seek=510 conv=notrunc 2>/dev/null
	[ "$(stat -f %z "$img.tmp")" -eq "$disk_bytes" ] ||
	    die "the image is not DISK_SIZE ($DISK_SIZE) large"
	mv "$img.tmp" "$img"

	# Every image gets the next build number; the mod built around it is
	# versioned <FreeBSD release>-build-<number> (see mod/).
	build=$(( $(cat "$WORK/build-number" 2>/dev/null || echo 0) + 1 ))
	echo "$build" > "$WORK/build-number"
	echo "$build" > "$IMAGES/build-number"
	msg "$(basename "$img") done, build $build"
}

# Open Firmware (SLOF) boots a PReP boot partition: FreeBSD's boot1.elf in
# it finds the UFS root on the disk and starts /boot/loader from it, as on
# FreeBSD's own powerpc64 VM images (release/tools/vmimage.subr). See
# DISK_SIZE in the configuration for the layout.
make_disk_prep() {
	prep_offset=4096
	boot_bytes=$mib
	root_bytes=$((disk_bytes - boot_bytes - swap_bytes))
	[ "$root_bytes" -gt 0 ] || die "DISK_SIZE $DISK_SIZE leaves no room for /"
	prep="$WORLD/$PREP_BOOT"
	[ -f "$prep" ] || die "no $PREP_BOOT in the world"
	[ "$(stat -f %z "$prep")" -le $((boot_bytes - prep_offset)) ] ||
	    die "$PREP_BOOT does not fit the PReP partition"
	msg "Making $img: $DISK_SIZE, PReP $(((boot_bytes - prep_offset) / 1024))K," \
	    "root $((root_bytes / 1024))K (${used}K used), swap $((swap_bytes / 1024))K"

	make_rootfs "$root_bytes"
	rm -f "$img.tmp"
	truncate -s "$boot_bytes" "$img.tmp"
	dd if="$prep" of="$img.tmp" bs=512 seek=$((prep_offset / 512)) \
	    conv=notrunc 2>/dev/null
	cat "$WORK/root.ufs" >> "$img.tmp"
	mbr_entry 0 "$prep_offset" $((boot_bytes - prep_offset)) 65 active
	mbr_entry 1 "$boot_bytes" "$root_bytes"
	[ "$swap_bytes" -eq 0 ] || make_swap 2
}

# The disk: MBR, ext2 boot area with /boot/Image for sedna-fw, UFS root.
# See DISK_SIZE in the configuration for the layout.
make_disk_sedna() {
	image_bytes=$(stat -f %z "$IMAGES/Image")
	if [ -n "$BOOTFS_SIZE" ]; then
		boot_bytes=$((BOOTFS_SIZE * mib))
	else
		# The kernel and ext2 metadata, rounded up to MiB. The image is
		# rebuilt with every kernel, so no room for a larger kernel.
		boot_bytes=$(( (image_bytes + mib / 2 + mib - 1) / mib * mib ))
	fi
	root_bytes=$((disk_bytes - boot_bytes - swap_bytes))
	[ "$root_bytes" -gt 0 ] || die "DISK_SIZE $DISK_SIZE leaves no room for /"

	bootdir="$WORK/bootfs"
	msg "Making $img: $DISK_SIZE, boot $((boot_bytes / 1024))K," \
	    "root $((root_bytes / 1024))K (${used}K used), swap $((swap_bytes / 1024))K"

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

	make_rootfs "$root_bytes"
	cat "$WORK/boot.ext2" "$WORK/root.ufs" > "$img.tmp"

	# MBR, written last: mke2fs clears the first sector. A FreeBSD (165)
	# partition for the root file system, and one for the swap space; the
	# boot area before them is unused space as far as partitioning goes.
	mbr_entry 0 "$boot_bytes" "$root_bytes"
	[ "$swap_bytes" -eq 0 ] || make_swap 1
	magic=$(od -An -tx1 -j1080 -N2 "$img.tmp" | tr -d ' ')
	[ "$magic" = "53ef" ] || die "the MBR clobbered the ext2 superblock"
}

stages=${*:-all}
[ "$stages" = all ] && stages=$STAGES
for s in $stages; do
	case "$s" in
	src|world|kernel|install|fetch|flua|firmware|rootfs) "stage_$s" ;;
	*) die "unknown stage $s" ;;
	esac
done
