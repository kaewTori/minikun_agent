#!/bin/sh

set -eu
umask 077

minikun_tls_root="$HOME/Library/Application Support/Minikun/tls"
minikun_root_key="$minikun_tls_root/minikun-local-ca.key"
minikun_root_pem="$minikun_tls_root/minikun-local-ca.pem"
minikun_root_cer="$minikun_tls_root/minikun-local-ca.cer"
minikun_server_cert="$minikun_tls_root/minikun-server.crt"
minikun_keystore="$minikun_tls_root/minikun.p12"
minikun_password_file="$minikun_tls_root/keystore-password.txt"

if [ -x /opt/homebrew/bin/openssl ]; then
  minikun_openssl=/opt/homebrew/bin/openssl
elif command -v openssl >/dev/null 2>&1; then
  minikun_openssl="$(command -v openssl)"
else
  echo "OpenSSL is required to create the Minikun local certificate" >&2
  exit 1
fi

mkdir -p "$minikun_tls_root"

if [ ! -s "$minikun_root_key" ] || [ ! -s "$minikun_root_pem" ]; then
  "$minikun_openssl" req -x509 -new -nodes -newkey rsa:3072 -sha256 -days 3650 \
    -keyout "$minikun_root_key" \
    -out "$minikun_root_pem" \
    -subj "/CN=Minikun Local Root CA/O=Minikun" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -addext "subjectKeyIdentifier=hash"
fi

if [ ! -s "$minikun_password_file" ]; then
  "$minikun_openssl" rand -hex 32 > "$minikun_password_file"
fi

minikun_temp_root="$(mktemp -d "${TMPDIR:-/tmp}/minikun-https.XXXXXX")"
trap 'rm -rf "$minikun_temp_root"' EXIT HUP INT TERM

minikun_lan_ip=""
for minikun_interface in en0 en1; do
  minikun_candidate_ip="$(/usr/sbin/ipconfig getifaddr "$minikun_interface" 2>/dev/null || true)"
  if [ -n "$minikun_candidate_ip" ]; then
    minikun_lan_ip="$minikun_candidate_ip"
    break
  fi
done
minikun_tailnet_ip=""
if [ -x /usr/local/bin/tailscale ]; then
  minikun_tailnet_ip="$(/usr/local/bin/tailscale ip -4 2>/dev/null | sed -n '1p' || true)"
fi

minikun_san="DNS:mini-kun,DNS:mini-kun.local,DNS:localhost,IP:127.0.0.1"
if [ -n "$minikun_lan_ip" ]; then
  minikun_san="$minikun_san,IP:$minikun_lan_ip"
fi
if [ -n "$minikun_tailnet_ip" ] && [ "$minikun_tailnet_ip" != "$minikun_lan_ip" ]; then
  minikun_san="$minikun_san,IP:$minikun_tailnet_ip"
fi

"$minikun_openssl" req -new -nodes -newkey rsa:2048 -sha256 \
  -keyout "$minikun_temp_root/server.key" \
  -out "$minikun_temp_root/server.csr" \
  -subj "/CN=mini-kun.local/O=Minikun"

{
  printf '%s\n' 'authorityKeyIdentifier=keyid,issuer'
  printf '%s\n' 'basicConstraints=critical,CA:FALSE'
  printf '%s\n' 'keyUsage=critical,digitalSignature,keyEncipherment'
  printf '%s\n' 'extendedKeyUsage=serverAuth'
  printf 'subjectAltName=%s\n' "$minikun_san"
} > "$minikun_temp_root/server.ext"

"$minikun_openssl" x509 -req -sha256 -days 397 \
  -in "$minikun_temp_root/server.csr" \
  -CA "$minikun_root_pem" \
  -CAkey "$minikun_root_key" \
  -CAcreateserial \
  -out "$minikun_temp_root/server.crt" \
  -extfile "$minikun_temp_root/server.ext"

"$minikun_openssl" pkcs12 -export \
  -name minikun \
  -inkey "$minikun_temp_root/server.key" \
  -in "$minikun_temp_root/server.crt" \
  -certfile "$minikun_root_pem" \
  -out "$minikun_temp_root/minikun.p12" \
  -passout "file:$minikun_password_file"

"$minikun_openssl" x509 -in "$minikun_root_pem" -outform DER -out "$minikun_temp_root/minikun-local-ca.cer"
mv "$minikun_temp_root/server.crt" "$minikun_server_cert"
mv "$minikun_temp_root/minikun.p12" "$minikun_keystore"
mv "$minikun_temp_root/minikun-local-ca.cer" "$minikun_root_cer"
chmod 600 "$minikun_root_key" "$minikun_root_pem" "$minikun_root_cer" \
  "$minikun_server_cert" "$minikun_keystore" "$minikun_password_file"

minikun_login_keychain="$HOME/Library/Keychains/login.keychain-db"
minikun_root_fingerprint="$($minikun_openssl x509 \
  -in "$minikun_root_pem" -noout -fingerprint -sha256 \
  | sed 's/^[^=]*=//;s/://g')"
minikun_keychain_certificates="$(security find-certificate -a -c 'Minikun Local Root CA' \
  -Z "$minikun_login_keychain" 2>/dev/null || true)"
minikun_ca_trusted=false
case "$minikun_keychain_certificates" in
  *"$minikun_root_fingerprint"*)
    minikun_ca_trusted=true
    ;;
  *)
    if [ "${MINIKUN_TRUST_LOCAL_CA:-false}" = "true" ]; then
      echo "Trusting Minikun Local Root CA in the login keychain"
      if ! security add-trusted-cert -r trustRoot \
        -k "$minikun_login_keychain" "$minikun_root_cer"; then
        echo "Unable to trust the Minikun Local Root CA." >&2
        echo "Run this deploy directly from Terminal and approve the macOS keychain prompt." >&2
        exit 1
      fi
      minikun_ca_trusted=true
    else
      echo "Minikun Local Root CA is not trusted; HTTPS may be rejected by browsers."
      echo "To opt in, rerun with MINIKUN_TRUST_LOCAL_CA=true."
    fi
    ;;
esac

"$minikun_openssl" x509 -in "$minikun_server_cert" -noout -ext subjectAltName | grep -q 'DNS:mini-kun'

echo "Minikun local HTTPS certificate refreshed"
echo "  http://127.0.0.1:8080/cockpit/"
if [ "$minikun_ca_trusted" = "true" ]; then
  echo "  https://mini-kun.local:8443/cockpit/"
fi
if [ -n "$minikun_lan_ip" ]; then
  echo "  http://$minikun_lan_ip:8080/cockpit/"
  if [ "$minikun_ca_trusted" = "true" ]; then
    echo "  https://$minikun_lan_ip:8443/cockpit/"
  fi
fi
