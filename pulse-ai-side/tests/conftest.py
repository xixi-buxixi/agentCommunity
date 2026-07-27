"""
Test configuration.

Settings now fail closed: constructing them without SERVICE_TOKEN raises, which is
the desired production behaviour. Tests therefore provide an explicit token here,
before any app module (and thus the settings singleton) is imported.
"""

import os

os.environ.setdefault("SERVICE_TOKEN", "test-service-token")
# Keep the .env of a developer machine from leaking into the test run
os.environ.setdefault("DEBUG", "false")
os.environ.setdefault("LLM_HOST_ALLOWLIST", "")
# The SSRF guard resolves DNS. Some networks (corporate DNS, VPNs, CI runners with
# split-horizon resolvers) map public provider names onto private addresses, which
# would make model-construction tests fail for reasons unrelated to the code under
# test. The guard itself is covered directly in test_url_guard.py, where the policy
# is passed explicitly instead of read from the environment.
os.environ.setdefault("BLOCK_PRIVATE_LLM_TARGETS", "false")
