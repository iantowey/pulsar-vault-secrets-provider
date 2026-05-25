#
# Vault-backed SecretsProvider for Apache Pulsar Python functions.
#
# This module is the Python counterpart of the Java VaultSecretsProvider.
# It is loaded by the Pulsar Python function instance when the
# VaultSecretsProviderConfigurator returns "vault_secrets_provider.VaultSecretsProvider"
# for PYTHON-runtime functions.
#
# No external dependencies beyond the Python 3 standard library are required.
#

import json
import logging
import ssl
import time
import urllib.error
import urllib.request

from secretsprovider import SecretsProvider

log = logging.getLogger("VaultSecretsProvider")


class VaultSecretsProvider(SecretsProvider):
    """Fetches secrets from HashiCorp Vault via Vault Agent.

    Receives the same configuration map that the Java VaultSecretsProvider
    receives (serialised as JSON by the Pulsar functions worker and passed
    via the ``--secrets_provider_config`` CLI flag).

    Authentication is handled externally by Vault Agent (auto-auth +
    caching proxy), so this provider makes unauthenticated requests to
    the agent endpoint.

    Config keys (mirrors the Java provider):
        vaultAddress          - Vault Agent URL (required)
        vaultFunctionPath     - pre-computed Vault path for this function (required, set by configurator)
        vaultNamespace        - Vault namespace header
        vaultEngineVersion    - KV engine version, "1" or "2" (default: "1")
        vaultOpenTimeout      - connection timeout in seconds (default: 5)
        vaultReadTimeout      - read timeout in seconds (default: 30)
        vaultMaxRetries       - number of retries on failure (default: 3)
        vaultRetryInterval    - milliseconds between retries (default: 1000)
        vaultSslVerify        - whether to verify TLS certs (default: "true")
        vaultSslPemPath       - path to a custom CA PEM file
    """

    def __init__(self):
        self._address = None
        self._namespace = None
        self._function_path = None
        self._engine_version = 1
        self._read_timeout = 30
        self._open_timeout = 5
        self._max_retries = 3
        self._retry_interval_ms = 1000
        self._ssl_context = None

    # ------------------------------------------------------------------
    # SecretsProvider interface
    # ------------------------------------------------------------------

    def init(self, config):
        """Initialise the provider.  No authentication is performed here;
        Vault Agent handles auth transparently via its caching proxy."""
        if config is None:
            raise ValueError("VaultSecretsProvider config must not be None")

        self._address = self._require(config, "vaultAddress")
        self._namespace = config.get("vaultNamespace")
        self._function_path = config.get("vaultFunctionPath")
        self._engine_version = int(config.get("vaultEngineVersion", "1"))
        self._open_timeout = int(config.get("vaultOpenTimeout", "5"))
        self._read_timeout = int(config.get("vaultReadTimeout", "30"))
        self._max_retries = int(config.get("vaultMaxRetries", "3"))
        self._retry_interval_ms = int(config.get("vaultRetryInterval", "1000"))

        ssl_verify = config.get("vaultSslVerify", "true").lower() == "true"
        ssl_pem_path = config.get("vaultSslPemPath")
        self._ssl_context = self._build_ssl_context(ssl_verify, ssl_pem_path)

        log.info("Vault address: %s", self._address)
        log.info("Vault namespace: %s", self._namespace)
        log.info("Vault KV engine version: %d", self._engine_version)
        log.info("Vault function path: %s", self._function_path)
        log.info("Vault SSL verify: %s", ssl_verify)

    def provide_secret(self, secret_name, path_to_secret):
        """Return the value of *secret_name* stored at the function's Vault path.

        ``path_to_secret`` is ignored (same behaviour as the Java provider).
        """
        return self._read_secret(secret_name, self._function_path)

    # ------------------------------------------------------------------
    # Vault HTTP helpers
    # ------------------------------------------------------------------

    def _read_secret(self, secret_name, path):
        """Read a single key from the Vault KV store at *path*."""
        if self._address is None:
            raise RuntimeError(
                "VaultSecretsProvider has not been initialised; call init() first"
            )
        if path is None:
            log.warning("Vault path is None for secretName=%s", secret_name)
            return None

        url = "%s/v1/%s" % (self._address.rstrip("/"), path)
        log.info("Providing secret for key='%s' at path='%s'", secret_name, path)

        body = self._vault_request("GET", url)
        if body is None:
            log.warning("No response from Vault for path=%s", path)
            return None

        data = body.get("data")
        if data is None:
            log.warning("No data found at Vault path=%s", path)
            return None

        # KV v2: secrets are nested under data.data
        if self._engine_version == 2 and isinstance(data.get("data"), dict):
            data = data["data"]

        value = data.get(secret_name)
        if value is None:
            log.warning("Key '%s' not found at Vault path=%s", secret_name, path)
        else:
            log.info(
                "Successfully resolved secret at path='%s', key='%s'", path, secret_name
            )
        return value

    def _vault_request(self, method, url, data=None):
        """Execute an HTTP request against Vault with retries.

        Returns the parsed JSON response body, or None on failure.
        """
        headers = {"Content-Type": "application/json"}
        if self._namespace:
            headers["X-Vault-Namespace"] = self._namespace

        encoded_data = json.dumps(data).encode("utf-8") if data is not None else None

        last_exc = None
        for attempt in range(1, self._max_retries + 1):
            try:
                req = urllib.request.Request(
                    url, data=encoded_data, headers=headers, method=method
                )
                with urllib.request.urlopen(
                    req, timeout=self._read_timeout, context=self._ssl_context
                ) as resp:
                    return json.loads(resp.read().decode("utf-8"))
            except urllib.error.HTTPError as exc:
                last_exc = exc
                body = exc.read().decode("utf-8", errors="replace")
                log.error(
                    "Vault %s %s returned HTTP %d (attempt %d/%d): %s",
                    method,
                    url,
                    exc.code,
                    attempt,
                    self._max_retries,
                    body,
                )
            except (urllib.error.URLError, OSError) as exc:
                last_exc = exc
                log.error(
                    "Vault %s %s failed (attempt %d/%d): %s",
                    method,
                    url,
                    attempt,
                    self._max_retries,
                    exc,
                )

            if attempt < self._max_retries:
                time.sleep(self._retry_interval_ms / 1000.0)

        log.error("All %d attempts to %s %s failed", self._max_retries, method, url)
        if isinstance(last_exc, urllib.error.HTTPError) and last_exc.code == 403:
            raise RuntimeError(
                "Vault authentication/authorisation failed for %s %s" % (method, url)
            ) from last_exc
        return None

    # ------------------------------------------------------------------
    # Internal utilities
    # ------------------------------------------------------------------

    @staticmethod
    def _require(config, key):
        value = config.get(key)
        if not value or not value.strip():
            raise ValueError("Missing required Vault configuration: %s" % key)
        return value.strip()

    @staticmethod
    def _build_ssl_context(verify, pem_path):
        """Build an ssl.SSLContext matching the provider's TLS settings."""
        if not verify:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            return ctx
        if pem_path:
            ctx = ssl.create_default_context(cafile=pem_path)
            return ctx
        return None  # use system defaults
