#!/bin/sh
# Backport the upstream QS-Hex load-format fix to stable NUT 2.8.5.
set -eu
case "$(uname -m)" in arm64) nut_prefix=/opt/homebrew ;; *) nut_prefix=/usr/local ;; esac
command -v pkg-config >/dev/null || HOMEBREW_NO_AUTO_UPDATE=1 "$nut_prefix/bin/brew" install pkgconf
build_root="$(mktemp -d /private/tmp/minikun-nut-build.XXXXXX)"
archive="$build_root/nut-2.8.5.tar.gz"
curl --fail --location --silent --show-error \
  https://github.com/networkupstools/nut/releases/download/v2.8.5/nut-2.8.5.tar.gz --output "$archive"
printf '18bf32e59eb764b13da3c4fa70384926d7fa584cb31d2fe7f137a570633eeec1  %s\n' "$archive" | shasum -a 256 -c -
tar -xzf "$archive" -C "$build_root"
cd "$build_root/nut-2.8.5"
python3 - <<'PY'
from pathlib import Path
p = Path('drivers/nutdrv_qx_voltronic-qs-hex.c')
rows = p.read_text().splitlines(keepends=True)
matches = [i for i, row in enumerate(rows) if '"ups.load"' in row]
assert len(matches) == 1 and '"%d"' in rows[matches[0]], 'Unexpected source; no patch applied'
rows[matches[0]] = rows[matches[0]].replace('"%d"', '"%ld"')
p.write_text(''.join(rows).replace('"Voltronic-QS-Hex 0.11"', '"Voltronic-QS-Hex 0.11-minikun-load-fix"'))
PY
export PKG_CONFIG_PATH="$nut_prefix/opt/libusb/lib/pkgconfig"
./configure --prefix=/usr/local/libexec/minikun-nut --sysconfdir="$nut_prefix/etc/nut" \
  --with-statepath="$nut_prefix/var/state/ups" --with-pidpath="$nut_prefix/var/run" \
  --with-drivers=nutdrv_qx --with-usb=libusb-1.0 --without-openssl --without-serial \
  --without-snmp --without-neon --without-ipmi --without-powerman --without-modbus \
  --without-avahi --without-wrap --without-libltdl --without-doc --without-cgi \
  --without-dev --without-nut_monitor --without-pynut > "$build_root/configure.log" 2>&1
make -j4 -C common > "$build_root/build.log" 2>&1
make -j4 -C drivers nutdrv_qx >> "$build_root/build.log" 2>&1
./drivers/nutdrv_qx -V
printf 'Built driver: %s/nut-2.8.5/drivers/nutdrv_qx\nBuild logs: %s\n' "$build_root" "$build_root"
