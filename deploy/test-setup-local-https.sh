#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
test_home="$(mktemp -d)"
trap 'rm -rf "$test_home"' EXIT HUP INT TERM
cert="$test_home/Library/Application Support/Minikun/tls/minikun-server.crt"
store="$test_home/Library/Application Support/Minikun/tls/minikun.p12"

HOME="$test_home" /bin/sh "$script_dir/setup-local-https.sh" >/dev/null 2>&1
cp "$cert" "$test_home/first.crt"
cp "$store" "$test_home/first.p12"
HOME="$test_home" /bin/sh "$script_dir/setup-local-https.sh" >/dev/null 2>&1
cmp -s "$cert" "$test_home/first.crt"
cmp -s "$store" "$test_home/first.p12"
rm "$store"
HOME="$test_home" /bin/sh "$script_dir/setup-local-https.sh" >/dev/null 2>&1
if cmp -s "$cert" "$test_home/first.crt"; then
  echo 'certificate was not renewed after losing its keystore' >&2
  exit 1
fi
echo 'HTTPS certificate reuse and renewal OK'
