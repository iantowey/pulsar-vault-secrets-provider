package com.ostk.pulsar.extensions.secrets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jopenlibs.vault.SslConfig;
import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultConfig;
import io.github.jopenlibs.vault.VaultException;
import io.github.jopenlibs.vault.response.LogicalResponse;
import org.apache.pulsar.functions.secretsprovider.SecretsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static com.ostk.pulsar.extensions.secrets.Utils.*;

public class VaultSecretsProvider implements SecretsProvider {

    private static final Logger log = LoggerFactory.getLogger(VaultSecretsProvider.class);

    /** Pattern for inline secret references: {@code vault:<path>:<key>} */
    static final Pattern SECRET_REF_PATTERN = Pattern.compile("^vault:([^:]+):(.+)$");

    // Config keys
    static final String CFG_VAULT_ADDRESS = "vaultAddress";
    public static final String CFG_NAMESPACE = "vaultNamespace";
    static final String CFG_ENGINE_VERSION = "vaultEngineVersion";
    static final String CFG_OPEN_TIMEOUT = "vaultOpenTimeout";
    static final String CFG_READ_TIMEOUT = "vaultReadTimeout";
    static final String CFG_MAX_RETRIES = "vaultMaxRetries";
    static final String CFG_RETRY_INTERVAL = "vaultRetryInterval";
    static final String CFG_SSL_VERIFY = "vaultSslVerify";
    static final String CFG_SSL_PEM_PATH = "vaultSslPemPath";

    private Vault vault;
    private int maxRetries;
    private int retryInterval;
    private int engineVersion;
    private String namespace;
    private String functionPath;
    private String pulsarCluster;
    @Override
    public void init(Map<String, String> config) {
        log.info("Initialising VaultSecretsProvider...");

        String address = requireConfig(config, CFG_VAULT_ADDRESS);
        log.info("Vault address: {}", address);

        namespace = config.get(CFG_NAMESPACE);
        log.info("Vault namespace: {}", namespace);

        this.engineVersion = intOrDefault(config, CFG_ENGINE_VERSION, 1);
        log.info("Vault KV engine version: {}", this.engineVersion);

        int openTimeout = intOrDefault(config, CFG_OPEN_TIMEOUT, 5);
        log.info("Vault open timeout: {}s", openTimeout);

        int readTimeout = intOrDefault(config, CFG_READ_TIMEOUT, 30);
        log.info("Vault read timeout: {}s", readTimeout);

        this.maxRetries = intOrDefault(config, CFG_MAX_RETRIES, 3);
        log.info("Vault max retries: {}", this.maxRetries);

        this.retryInterval = intOrDefault(config, CFG_RETRY_INTERVAL, 1000);
        log.info("Vault retry interval: {}ms", this.retryInterval);

        boolean sslVerify = boolOrDefault(config, CFG_SSL_VERIFY, true);
        log.info("Vault SSL verify: {}", sslVerify);

        String sslPemPath = config.get(CFG_SSL_PEM_PATH);
        log.info("Vault SSL PEM path: {}", sslPemPath);

        this.functionPath = config.get(VaultSecretsProviderConfigurator.CFG_VAULT_FUNCTION_PATH);
        log.info("Vault function-specific path: {}", this.functionPath);

        this.pulsarCluster = config.get(VaultSecretsProviderConfigurator.CFG_PULSAR_CLUSTER);
        log.info("Pulsar cluster: {}", this.pulsarCluster);

        try {
            SslConfig sslConfig = new SslConfig();
            if (!sslVerify) {
                sslConfig.verify(false);
            }
            if (sslPemPath != null && !sslPemPath.isBlank()) {
                sslConfig.pemFile(new java.io.File(sslPemPath));
            }
            sslConfig.build();

            // No token — authentication is handled by Vault Agent, which
            // injects its auto-auth token into proxied requests.
            VaultConfig vaultConfig = new VaultConfig()
                    .address(address)
                    .openTimeout(openTimeout)
                    .readTimeout(readTimeout)
                    .sslConfig(sslConfig);

            if (namespace != null && !namespace.isBlank()) {
                vaultConfig.nameSpace(namespace);
            }

            vaultConfig.build();

            this.vault = Vault.create(vaultConfig, engineVersion);

        } catch (VaultException e) {
            throw new RuntimeException("Failed to initialise Vault secrets provider", e);
        }
    }

    @Override
    public String provideSecret(String secretName, Object pathToSecret) {
        return getSecretFromVault(secretName, this.functionPath);
    }

    private String getSecretFromVault(String secretName, String path) {
        if (vault == null) {
            throw new IllegalStateException("VaultSecretsProvider has not been initialised; call init() first");
        }
        if (path == null) {
            log.warn("Vault path is null for secretName={}", secretName);
            return null;
        }

        log.info("Providing secret for key='{}' at path='{}'", secretName, path);

        try {
            LogicalResponse response = vault.withRetries(maxRetries, retryInterval)
                    .logical()
                    .read(path);

            if (response == null) {
                log.warn("No response from Vault for path={}", path);
                return null;
            }
            if (response.getData() == null) {
                log.warn("No data found at Vault path={}", path);
                return null;
            }

            Map<String, String> data = response.getData();
                // For KV v2, the secrets are nested under a "data" key.
                // The jopenlibs-vault library may return this nested map as a string.
            String nestedDataString = data.get("data");
            if (nestedDataString != null) {
                try {
                    log.info("*********** nestedDataString={}", nestedDataString);
                    JsonObject nestedJson = JsonParser.parseString(nestedDataString).getAsJsonObject();
                    Map<String, String> nestedMap = new HashMap<>();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : nestedJson.entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) {
                            nestedMap.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                    data = nestedMap;
                } catch (Exception e) {
                    log.warn("Failed to parse nested KV v2 data for path={}", path, e);
                    return null;
                }
            }

            String value = data.get(secretName);
            if (value == null) {
                log.warn("Key '{}' not found at Vault path={}", secretName, path);
            } else {
                log.info("Successfully resolved secret at path='{}', key='{}'", path, secretName);
            }
            return value;

        } catch (VaultException e) {
            log.error("Failed to read secret at path={} key={}", path, secretName, e);
            return null;
        }
    }

    public void setVault(Vault vault) {
        this.vault = vault;
    }

    public void setMaxRetries(int i) {
        this.maxRetries = i;
    }

    public void setRetryInterval(int i) {
        this.retryInterval = i;
    }

    public Object interpolateSecretForValue(Object o) {
        if (o instanceof String) {
            String s = (String) o;
            var matcher = SECRET_REF_PATTERN.matcher(s);
            if (matcher.matches()) {
                String path = matcher.group(1);
                String key = matcher.group(2);
                log.info("Interpolating secret from Vault: path={}, key={}", path, key);
                return getSecretFromVault(key, path);
            }
        }
        return null;
    }
}
