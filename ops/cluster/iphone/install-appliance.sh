#!/bin/sh
# Run from a pinned SSH root session; source paths are hash-pinned by caller.
set -eu
umask 077
export PATH=/sbin:/usr/sbin:/bin:/usr/bin

base=/var/lib/iphone-node3
settings=/etc/iphone-node3
service=/etc/init.d/iphone-node3-admission
test "$(id -u)" -eq 0
test -d "$settings" && test ! -L "$settings"
test "$(stat -c '%u:%a' "$settings")" = '0:700'
test ! -e "$base" && test ! -L "$base"
test ! -e "$service" && test ! -L "$service"
test "$#" -eq 4 || test "$#" -eq 5
gate=$1
gate_sha=$2
entry=$3
entry_sha=$4

if [ "$#" -eq 5 ]; then
    stage=$5
    case "$stage" in /var/lib/iphone-node3-stage.????????) ;; *) exit 2 ;; esac
    test "$(dirname "$stage")" = /var/lib
    test -d "$stage" && test ! -L "$stage"
    test "$(stat -c '%u:%a' "$stage")" = '0:700'
else
    stage=$(mktemp -d /var/lib/iphone-node3-stage.XXXXXXXX)
    cleanup() { test ! -d "$stage" || rm -r -- "$stage"; }
    trap cleanup EXIT
fi
mkdir -p -m 700 "$stage/program"
cp -P -- "$gate" "$stage/program/third_node_gate.py"
cp -P -- "$entry" "$stage/program/lab_entry.py"
test ! -L "$stage/program/third_node_gate.py" && test ! -L "$stage/program/lab_entry.py"
printf '%s  %s\n%s  %s\n' "$gate_sha" "$stage/program/third_node_gate.py" "$entry_sha" "$stage/program/lab_entry.py" | sha256sum -c -
chmod 600 "$stage/program/"*.py

apk_root=$stage/python-root
mkdir -p -m 700 "$apk_root"
test ! -L "$apk_root"
mkdir -p "$apk_root/etc/apk"
if [ ! -d "$apk_root/etc/apk/keys" ]; then
    cp -R /etc/apk/keys "$apk_root/etc/apk/keys"
fi
# Package and index signatures are checked with the guest's existing trusted
# Alpine keys. Nothing is added to the guest system's package database.
apk --root "$apk_root" --initdb --no-scripts --no-cache --repositories-file /dev/null \
    --repository https://dl-cdn.alpinelinux.org/alpine/v3.24/main add python3
test -x "$apk_root/lib/ld-musl-x86_64.so.1" && test -x "$apk_root/usr/bin/python3"

cat > "$stage/run" <<'EOF'
#!/bin/sh
set -eu
umask 077
cd /var/lib/iphone-node3/program
exec env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin HOME=/root PYTHONHOME=/var/lib/iphone-node3/python-root/usr \
    /var/lib/iphone-node3/python-root/lib/ld-musl-x86_64.so.1 \
    --library-path /var/lib/iphone-node3/python-root/lib:/var/lib/iphone-node3/python-root/usr/lib \
    /var/lib/iphone-node3/python-root/usr/bin/python3 -s -B /var/lib/iphone-node3/program/third_node_gate.py "$@"
EOF
chmod 700 "$stage/run"

cat > "$stage/admission.init" <<'EOF'
#!/sbin/openrc-run
name="iPhone application admission"
supervisor="supervise-daemon"
command="/var/lib/iphone-node3/run"
command_user="root:root"
respawn_delay=5
respawn_max=5
respawn_period=60
output_log="/var/log/iphone-node3-admission.log"
error_log="/var/log/iphone-node3-admission.log"
depend() { need net iphone-node3-monitor-tunnel; }
start_pre() { checkpath --file --mode 0600 --owner root:root "$output_log"; }
EOF
chmod 755 "$stage/admission.init"

mv -- "$stage" "$base"
install -m 0755 "$base/admission.init" "$service"
echo 'Root-owned isolated admission runtime installed. Start it after writing the private config and starting the monitor SSH tunnel.'
