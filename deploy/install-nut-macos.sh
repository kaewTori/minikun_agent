#!/bin/sh
set -eu

script_root="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
case "$(uname -m)" in
  arm64) nut_prefix=/opt/homebrew ;;
  *) nut_prefix=/usr/local ;;
esac

if [ "${1:-}" = --system-driver ]; then
  [ "$(id -u)" -eq 0 ] || { echo 'This step requires administrator authentication.' >&2; exit 1; }
  driver_plist=/Library/LaunchDaemons/com.minikun.nut-driver.plist
  fixed_driver=/usr/local/libexec/minikun-nut/nutdrv_qx
  if [ -f "$script_root/nutdrv_qx.load-fixed" ]; then
    /usr/bin/install -d -o root -g wheel -m 755 /usr/local/libexec/minikun-nut
    /usr/bin/install -o root -g wheel -m 755 "$script_root/nutdrv_qx.load-fixed" "$fixed_driver.new"
    /bin/mv "$fixed_driver.new" "$fixed_driver"
  fi
  # Only the USB driver needs root on this Mac: libusb must release Apple's HID claim.
  # The local data server and agent run as the logged-in user.
  /usr/bin/sed "s|/opt/homebrew|$nut_prefix|g" "$script_root/com.minikun.nut-driver.plist" > "$driver_plist"
  if [ -x "$fixed_driver" ] && [ "${2:-}" != --stock-driver ]; then
    /usr/libexec/PlistBuddy -c "Set :ProgramArguments:0 $fixed_driver" "$driver_plist"
  fi
  /usr/sbin/chown root:wheel "$driver_plist"
  /bin/chmod 644 "$driver_plist"
  /usr/bin/plutil -lint "$driver_plist"
  /bin/launchctl bootout system/com.minikun.nut-driver 2>/dev/null || true
  /bin/launchctl enable system/com.minikun.nut-driver
  # launchd may still be removing the previous job after bootout returns.
  attempt=0
  until /bin/launchctl bootstrap system "$driver_plist"; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 5 ] || exit 1
    /bin/sleep 1
  done
  exit 0
fi

[ "$(uname -s)" = Darwin ] || { echo 'Use the Linux instructions in docs/ups-nut.md.' >&2; exit 1; }
[ "$(id -u)" -ne 0 ] || { echo 'Run this installer as the logged-in user, not with sudo.' >&2; exit 1; }
if [ ! -x "$nut_prefix/opt/nut/bin/nutdrv_qx" ]; then
  HOMEBREW_NO_AUTO_UPDATE=1 "$nut_prefix/bin/brew" install nut
fi
config="$nut_prefix/etc/nut"
state="$nut_prefix/var/state/ups"
mkdir -p "$config" "$state" "$HOME/Library/LaunchAgents" "$HOME/Library/Logs/Minikun"
# Preserve configuration on reruns; never create control users or enable upsmon.
if [ ! -f "$config/ups.conf" ]; then
  cat > "$config/ups.conf" <<'EOF'
pollinterval = 5
[cleanline]
    driver = nutdrv_qx
    port = auto
    vendorid = 0665
    productid = 5161
    protocol = voltronic-qs-hex
    desc = "CLEANLINE D-2000L USB"
    default.ups.mfr = CLEANLINE
    default.ups.model = D-2000L
    default.ups.power.nominal = 2000
    default.ups.realpower.nominal = 1200
    pollfreq = 30
EOF
fi
if [ ! -f "$config/upsd.conf" ]; then
  printf 'LISTEN 127.0.0.1 3493\nSTATEPATH %s\n' "$state" > "$config/upsd.conf"
fi
[ -f "$config/upsd.users" ] || printf '# No write/control users configured.\n' > "$config/upsd.users"
[ -f "$config/nut.conf" ] || printf 'MODE=netserver\n' > "$config/nut.conf"
chmod 640 "$config/ups.conf" "$config/upsd.conf" "$config/upsd.users" "$config/nut.conf"
chmod 750 "$state"

server_plist="$HOME/Library/LaunchAgents/com.minikun.nut-server.plist"
cat > "$server_plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>com.minikun.nut-server</string>
<key>ProgramArguments</key><array>
<string>$nut_prefix/opt/nut/sbin/upsd</string><string>-F</string>
<string>-u</string><string>$(id -un)</string>
</array>
<key>EnvironmentVariables</key><dict><key>NUT_QUIET_INIT_UPSNOTIFY</key><string>true</string></dict>
<key>RunAtLoad</key><true/>
<key>KeepAlive</key><true/>
<key>ThrottleInterval</key><integer>15</integer>
<key>StandardOutPath</key><string>$HOME/Library/Logs/Minikun/nut-server.log</string>
<key>StandardErrorPath</key><string>$HOME/Library/Logs/Minikun/nut-server.log</string>
</dict></plist>
EOF
plutil -lint "$server_plist"
server_service="gui/$(id -u)/com.minikun.nut-server"
launchctl bootout "$server_service" 2>/dev/null || true
launchctl enable "$server_service"
launchctl bootstrap "gui/$(id -u)" "$server_plist"
printf 'Local NUT data server installed. Install the USB driver with:\n'
printf 'sudo /bin/sh "%s/install-nut-macos.sh" --system-driver\n' "$script_root"
