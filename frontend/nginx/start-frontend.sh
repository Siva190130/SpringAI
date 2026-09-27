#!/bin/sh
set -eu
umask 077

fail() {
    echo "Frontend configuration error: $1" >&2
    exit 1
}

# Reject configuration injection; only an origin (no credentials/path/query) is accepted.
case "${BACKEND_URL:-}" in
    *[!A-Za-z0-9:./-]*) fail 'BACKEND_URL contains unsupported characters.' ;;
esac
printf '%s' "${BACKEND_URL:-}" | grep -Eq '^https?://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$' \
    || fail 'BACKEND_URL must be an http(s) origin with a DNS name or IPv4 address and optional port.'
case "${CHAT_API_KEY:-}" in
    ''|*[!A-Za-z0-9_-]*) fail 'CHAT_API_KEY must contain only letters, digits, underscores, or hyphens.' ;;
esac
[ "${#CHAT_API_KEY}" -ge 32 ] && [ "${#CHAT_API_KEY}" -le 256 ] \
    || fail 'CHAT_API_KEY must contain 32-256 characters.'

BACKEND_AUTHORITY=${BACKEND_URL#*://}
BACKEND_HOST=${BACKEND_AUTHORITY%%:*}
# Docker supplies 127.0.0.11; other hosts may supply their own DNS resolver.
NGINX_RESOLVER=${NGINX_RESOLVER:-$(awk '$1 == "nameserver" { print $2; exit }' /etc/resolv.conf)}
case "$NGINX_RESOLVER" in
    *[!0-9.]*) fail 'NGINX_RESOLVER must contain only an IPv4 address.' ;;
esac
printf '%s' "$NGINX_RESOLVER" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' \
    || fail 'NGINX_RESOLVER must be an IPv4 DNS resolver address.'
export BACKEND_AUTHORITY BACKEND_HOST NGINX_RESOLVER

# Substitute only these variables, preserving Nginx's $uri and $request_uri.
envsubst '${BACKEND_URL} ${BACKEND_AUTHORITY} ${BACKEND_HOST} ${NGINX_RESOLVER} ${CHAT_API_KEY}' \
    < /etc/nginx/templates/frontend.conf.template > /tmp/frontend.conf
nginx -t
exec "$@"
