#!/system/bin/sh
set -eu
# Debug PoC only: run-as exchanges the random key without publishing it.
# Release setup needs a separate authenticated provisioning mechanism.
base=/data/local/tmp/hingelab-helper
mkdir -p "$base"
chmod 700 "$base"
if [ -f "$base/pid" ]; then
  pid=$(cat "$base/pid")
  case "$pid" in *[!0-9]*|'') ;; *)
    if [ -r "/proc/$pid/cmdline" ] && tr '\000' ' ' < "/proc/$pid/cmdline" | grep -q 'dev.duohome.hingelab.ShellServer'; then kill "$pid" || true; fi
    ;;
  esac
fi
run-as dev.duohome.hingelab cat files/shell-token > "$base/token"
chmod 600 "$base/token"
apk=$(pm path dev.duohome.hingelab | head -n 1 | cut -d: -f2)
cp "$apk" "$base/helper.apk"
chmod 600 "$base/helper.apk"
nohup env CLASSPATH="$base/helper.apk" app_process /system/bin dev.duohome.hingelab.ShellServer "$base/token" </dev/null >"$base/status" 2>&1 &
echo $! > "$base/pid"
sleep 1
cat "$base/status"
