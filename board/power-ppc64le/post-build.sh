#!/bin/sh
#
# Runs after the root file system is populated, before the databases and the
# image are made. $1 is the target directory.
#
set -e

TARGET_DIR="$1"

# ---------------------------------------------------------------------------
# The console's getty entry, see /etc/ttys: no modem control, show /etc/issue
# and then a login prompt (root has no password). The line runs at OC2's
# 9600 baud.
# ---------------------------------------------------------------------------
cat >> "$TARGET_DIR/etc/gettytab" <<'GETTYTAB'

#
# Power BSD console.
#
power.console|Power BSD console:\
	:np:nc:sp#9600:if=/etc/issue:im=\r\n:lm=\r%h login\72 :
GETTYTAB

# ---------------------------------------------------------------------------
# The kernel, compressed: loader(8) loads /boot/kernel/kernel.gz in its
# place. About a third of the size, the disk is small.
# ---------------------------------------------------------------------------
gzip -9n "$TARGET_DIR/boot/kernel/kernel"

# ---------------------------------------------------------------------------
# The host name is rc.conf's: the build's /etc/hostname (TARGET_HOSTNAME) is
# for images without rc(8).
# ---------------------------------------------------------------------------
printf 'hostname="%s"\n' "$(cat "$TARGET_DIR/etc/hostname")" \
    >> "$TARGET_DIR/etc/rc.conf"
rm "$TARGET_DIR/etc/hostname"
