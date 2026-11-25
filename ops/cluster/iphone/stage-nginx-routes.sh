#!/bin/sh
# Prepare private include fragments only. The shared Nginx owner validates and
# activates them once the corresponding Tomcat contexts are ready.
set -eu
umask 077
root=/home/server/.local/iphone-deploy-20261121
configs=$root/nginx/private-app-routes
test "$(id -u)" -ne 0
mkdir -p -m 700 "$configs"
test ! -L "$configs"
test "$(stat -c '%u:%a' "$configs")" = "$(id -u):700"
test ! -e "$configs/iafm-atlas.conf" && test ! -L "$configs/iafm-atlas.conf"

ingress=$(sed -n 's/^ingress\.token=//p' "$root/tomcat-db-base/private/iafm-cluster.properties")
case "$ingress" in ''|*[!A-Za-z0-9_-]*) exit 2 ;; esac
test "${#ingress}" -ge 32 && test "${#ingress}" -le 256

tmp=$(mktemp "$configs/.iafm-atlas.XXXXXXXX")
trap 'rm -f -- "$tmp"' EXIT
cat > "$tmp" <<'NGINX'
location = /Investment_and_Financial_Management {
    if ($node3_https = "") { return 403; }
    return 308 /Investment_and_Financial_Management/;
}

location ^~ /Investment_and_Financial_Management/internal/ { return 404; }

location ^~ /Investment_and_Financial_Management/ {
    if ($node3_https = "") { return 403; }
    proxy_set_header Host $http_host;
    proxy_set_header X-Private-TLS-Bridge "";
    proxy_set_header X-Forwarded-Proto $node3_https;
    proxy_set_header X-Forwarded-Host $http_host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-IAFM-Ingress-Node 3;
NGINX
printf '    proxy_set_header X-IAFM-Ingress-Token "%s";\n' "$ingress" >> "$tmp"
unset ingress
cat >> "$tmp" <<'NGINX'
    proxy_pass http://127.0.0.1:18082;
}

location ^~ /atlas/api/ {
    if ($node3_https = "") { return 403; }
    proxy_set_header Host $http_host;
    proxy_set_header X-Private-TLS-Bridge "";
    proxy_set_header X-Forwarded-Proto $node3_https;
    proxy_set_header X-Forwarded-Host $http_host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_pass http://127.0.0.1:18082;
}
NGINX
mv -- "$tmp" "$configs/iafm-atlas.conf"
echo 'Private IAFM and ATLAS Nginx routes staged without reload.'
