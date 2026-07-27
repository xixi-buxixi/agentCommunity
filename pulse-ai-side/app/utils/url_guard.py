"""
SSRF guard for the caller-supplied LLM base_url.

The base_url arrives from user input (agents are created in the web UI), and this
service then sends the agent's API key to it. Two consequences follow:

1. Plain http would put the key on the wire in cleartext -> https is required
   unless explicitly allowed for local development.
2. Any reachable address could be targeted, which turns this service into an
   internal port scanner and a cloud-metadata reader (169.254.169.254). Private,
   loopback, link-local and multicast destinations are therefore rejected.

DNS is resolved here and every returned address is checked, which also blocks the
"public name resolving to a private address" trick. It does not eliminate DNS
rebinding (the name could resolve differently when httpx connects); an allowlist
(LLM_HOST_ALLOWLIST) is the defence for environments that need that guarantee.
"""

import ipaddress
import socket
from typing import List
from urllib.parse import urlparse

from app.config.settings import settings

# Ports of common internal services; the LLM APIs we support speak 443/80/8000+
_BLOCKED_PORTS = {22, 23, 25, 53, 111, 135, 139, 445, 1433, 1521, 2049, 3306, 3389,
                  5432, 5984, 6379, 9200, 11211, 27017}

_METADATA_HOSTS = {
    "169.254.169.254",          # AWS / GCP / Azure / Alibaba metadata
    "metadata.google.internal",
    "100.100.100.200",          # Alibaba Cloud metadata
}


class UrlNotAllowed(ValueError):
    """Raised when a base_url is rejected by the guard."""


def _is_forbidden_ip(ip: ipaddress._BaseAddress) -> bool:
    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )


def _resolve(host: str) -> List[ipaddress._BaseAddress]:
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror as exc:
        raise UrlNotAllowed(f"base_url host cannot be resolved: {host}") from exc

    addresses = []
    for info in infos:
        raw = info[4][0]
        try:
            addresses.append(ipaddress.ip_address(raw))
        except ValueError:
            continue
    if not addresses:
        raise UrlNotAllowed(f"base_url host has no usable address: {host}")
    return addresses


def validate_base_url(
    value: str,
    *,
    allow_http: bool = None,
    block_private: bool = None,
    allowlist: List[str] = None,
) -> str:
    """
    Validate and normalise a base_url.

    The policy knobs default to the process settings; they are parameters so the
    guard can be exercised directly in tests without depending on real DNS.

    :raises UrlNotAllowed: when the URL is malformed or points somewhere it must not
    """
    allow_http = settings.ALLOW_INSECURE_LLM_HTTP if allow_http is None else allow_http
    block_private = (
        settings.BLOCK_PRIVATE_LLM_TARGETS if block_private is None else block_private
    )
    allowlist = settings.host_allowlist if allowlist is None else allowlist

    candidate = (value or "").strip()
    if not candidate:
        raise UrlNotAllowed("base_url cannot be empty")

    parsed = urlparse(candidate)
    if parsed.scheme not in ("http", "https"):
        raise UrlNotAllowed("base_url must start with http:// or https://")

    if parsed.scheme == "http" and not allow_http:
        raise UrlNotAllowed(
            "base_url must use https: plain http would transmit the API key in cleartext"
        )

    host = (parsed.hostname or "").lower()
    if not host:
        raise UrlNotAllowed("base_url must contain a host")

    if allowlist:
        if host not in allowlist:
            raise UrlNotAllowed(f"base_url host is not allowlisted: {host}")
        # An explicit allowlist is the stronger control; skip the IP heuristics
        return candidate.rstrip("/")

    if host in _METADATA_HOSTS:
        raise UrlNotAllowed("base_url points at a cloud metadata endpoint")

    port = parsed.port
    if port is not None:
        if port in _BLOCKED_PORTS:
            raise UrlNotAllowed(f"base_url port is not allowed: {port}")
        if not 1 <= port <= 65535:
            raise UrlNotAllowed(f"base_url port is out of range: {port}")

    if block_private:
        try:
            literal = ipaddress.ip_address(host)
            addresses = [literal]
        except ValueError:
            addresses = _resolve(host)

        for address in addresses:
            if _is_forbidden_ip(address):
                raise UrlNotAllowed(
                    "base_url resolves to a non-public address "
                    f"({address}); internal targets are not allowed"
                )

    return candidate.rstrip("/")
