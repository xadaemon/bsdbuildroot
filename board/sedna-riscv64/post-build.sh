#!/bin/sh
#
# Runs after the root file system is populated, before the databases and the
# image are made. $1 is the target directory. Same role as the Linux tree's
# post-build.sh.
#
set -e

TARGET_DIR="$1"

# ---------------------------------------------------------------------------
# The console's getty entry, see /etc/ttys: no modem control, show /etc/issue
# and then a login prompt, like the Linux image (root has no password).
# ---------------------------------------------------------------------------
cat >> "$TARGET_DIR/etc/gettytab" <<'GETTYTAB'

#
# Sedna console.
#
sedna.console|Sedna console:\
	:np:nc:sp#115200:if=/etc/issue:im=\r\n:lm=\r%h login\72 :
GETTYTAB
