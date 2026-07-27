"""
Tests for the base_url SSRF guard.

The policy is passed explicitly so these tests do not depend on the ambient
environment, and IP literals are used wherever possible so they do not depend on
DNS either.
"""

import pytest

from app.utils.url_guard import UrlNotAllowed, validate_base_url


class TestSchemeRules:
    def test_https_is_accepted(self):
        assert validate_base_url(
            "https://api.openai.com/v1/", block_private=False
        ) == "https://api.openai.com/v1"

    def test_http_is_rejected_by_default(self):
        # Plain http would put the agent's API key on the wire in cleartext
        with pytest.raises(UrlNotAllowed):
            validate_base_url("http://api.openai.com/v1", block_private=False)

    def test_http_can_be_allowed_explicitly(self):
        assert validate_base_url(
            "http://api.openai.com/v1", allow_http=True, block_private=False
        ) == "http://api.openai.com/v1"

    def test_non_http_scheme_is_rejected(self):
        for url in ("file:///etc/passwd", "gopher://x", "ftp://host/v1", "not-a-url"):
            with pytest.raises(UrlNotAllowed):
                validate_base_url(url, block_private=False)

    def test_empty_is_rejected(self):
        with pytest.raises(UrlNotAllowed):
            validate_base_url("   ", block_private=False)


class TestPrivateTargetRules:
    @pytest.mark.parametrize(
        "url",
        [
            "https://127.0.0.1/v1",           # loopback
            "https://10.0.0.5/v1",            # private
            "https://192.168.1.10/v1",        # private
            "https://172.16.3.4/v1",          # private
            "https://169.254.169.254/latest", # cloud metadata
            "https://[::1]/v1",               # IPv6 loopback
            "https://0.0.0.0/v1",             # unspecified
        ],
    )
    def test_internal_addresses_are_rejected(self, url):
        with pytest.raises(UrlNotAllowed):
            validate_base_url(url, block_private=True)

    def test_metadata_hostname_is_rejected(self):
        with pytest.raises(UrlNotAllowed):
            validate_base_url("https://metadata.google.internal/v1", block_private=True)

    def test_public_literal_is_accepted(self):
        assert validate_base_url(
            "https://1.1.1.1/v1", block_private=True
        ) == "https://1.1.1.1/v1"

    def test_blocked_port_is_rejected(self):
        with pytest.raises(UrlNotAllowed):
            validate_base_url("https://1.1.1.1:6379/v1", block_private=True)


class TestAllowlist:
    def test_allowlisted_host_passes_without_ip_checks(self):
        assert validate_base_url(
            "https://api.deepseek.com/v1",
            block_private=True,
            allowlist=["api.deepseek.com"],
        ) == "https://api.deepseek.com/v1"

    def test_host_outside_allowlist_is_rejected(self):
        with pytest.raises(UrlNotAllowed):
            validate_base_url(
                "https://evil.example.com/v1",
                block_private=False,
                allowlist=["api.deepseek.com"],
            )
