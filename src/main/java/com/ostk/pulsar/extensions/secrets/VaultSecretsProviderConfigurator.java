package com.ostk.pulsar.extensions.secrets;

import com.google.gson.Gson;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.kubernetes.client.openapi.models.V1PodSpec;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.secretsproviderconfigurator.SecretsProviderConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class VaultSecretsProviderConfigurator implements SecretsProviderConfigurator {

    public static final String CFG_VAULT_FUNCTION_PATH = "vaultFunctionPath";
    public static final String CFG_PULSAR_CLUSTER = "pulsarCluster";

    public static final String CFG_VAULT_ROOT_PATH = "vaultRootPath";

    /**
     * The Python module.class path that the Pulsar Python instance will import
     * via {@code util.import_class()}.  The file {@code vault_secrets_provider.py}
     * must be present in the Python instance directory
     * (e.g. {@code /pulsar/instances/python-instance/}).
     */
    static final String PYTHON_PROVIDER_CLASS = "vault_secrets_provider.VaultSecretsProvider";

    private static final Logger log = LoggerFactory.getLogger(VaultSecretsProviderConfigurator.class);

    private Map<String, String> config;

    @Override
    public void init(Map<String, String> config) {
        this.config = config;
        log.info("Initialising VaultSecretsProviderConfigurator...{}", config.toString());
    }

    @Override
    public String getSecretsProviderClassName(Function.FunctionDetails functionDetails) {
        switch (functionDetails.getRuntime()) {
            case JAVA:
                return VaultSecretsProvider.class.getCanonicalName();
            case PYTHON:
                return PYTHON_PROVIDER_CLASS;
            case GO:
                // Go has no pluggable SecretsProvider interface; secrets must
                // be injected via environment variables in
                // configureProcessRuntimeSecretsProvider().
                return "";
            default:
                throw new IllegalArgumentException(
                        "Unsupported Pulsar function runtime: " + functionDetails.getRuntime());
        }
    }

    @Override
    public Map<String, String> getSecretsProviderConfig(Function.FunctionDetails functionDetails) {
        Map<String, String> functionConfig = new HashMap<>(this.config);
        String cluster = functionConfig.get(CFG_PULSAR_CLUSTER);
        if (cluster == null || cluster.isEmpty()) {
            throw new IllegalArgumentException("Pulsar cluster name is not configured. Please set '" + CFG_PULSAR_CLUSTER + "' in the secrets provider configuration.");
        }
        String componentType = functionDetails.getComponentType().name().toLowerCase();

        /**
         * the below creates vault paths of the form
         * if source    :  secret/umf/pulsar/midgard/source/public/default/secrets-demo-source
         * if sink      :  secret/umf/pulsar/midgard/sink/public/default/secrets-demo-sink
         * if functions :  secret/umf/pulsar/midgard/function/public/default/secrets-demo-func
         */
        String vaultFunctionPath = String.format("%s/%s/%s/%s/%s",
                functionConfig.get(CFG_VAULT_ROOT_PATH), // secret/umf/pulsar/midgard
                componentType,
                functionDetails.getTenant(),
                functionDetails.getNamespace(),
                functionDetails.getName());
        functionConfig.put(CFG_VAULT_FUNCTION_PATH, vaultFunctionPath);
        return functionConfig;
    }

    /**
     * Logs the full FunctionDetails protobuf as JSON for diagnostic purposes.
     */
    static String functionDetailsToJson(Function.FunctionDetails functionDetails) {
        try {
            return JsonFormat.printer()
                    .includingDefaultValueFields()
                    .preservingProtoFieldNames()
                    .print(functionDetails);
        } catch (InvalidProtocolBufferException e) {
            return "ERROR: failed to serialise FunctionDetails: " + e.getMessage();
        }
    }

    /**
     * Dumps FunctionDetails via SLF4J, stderr, and a file to guarantee visibility
     * regardless of log configuration. Remove once diagnostics are complete.
     */
    private void logFunctionDetailsAsJson(Function.FunctionDetails functionDetails) {
        String json = functionDetailsToJson(functionDetails);
        String header = String.format("FunctionDetails JSON dump for %s/%s/%s",
                functionDetails.getTenant(),
                functionDetails.getNamespace(),
                functionDetails.getName());

        // 1. SLF4J (may be filtered by log config)
        log.info("{}: {}", header, json);

        // 2. Direct to stderr (bypasses SLF4J/log4j2 entirely, shows in docker logs)
        System.err.println(">>> [VaultSecretsProviderConfigurator] " + header + ":");
        System.err.println(json);

        // 3. Write to file (survives even if stdout/stderr are redirected)
        try {
            Path dumpDir = Paths.get("/tmp/vault-secrets-diag");
            Files.createDirectories(dumpDir);
            String filename = String.format("function-details-%s-%s-%s-%d.json",
                    functionDetails.getTenant(),
                    functionDetails.getNamespace(),
                    functionDetails.getName(),
                    Instant.now().toEpochMilli());
            Path dumpFile = dumpDir.resolve(filename);
            Files.writeString(dumpFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.err.println(">>> [VaultSecretsProviderConfigurator] Wrote dump to: " + dumpFile);
        } catch (IOException e) {
            System.err.println(">>> [VaultSecretsProviderConfigurator] Failed to write dump file: " + e.getMessage());
        }
    }

    @Override
    public void configureKubernetesRuntimeSecretsProvider(V1PodSpec podSpec, String functionsContainerName, Function.FunctionDetails functionDetails) {

    }

    @Override
    public void configureProcessRuntimeSecretsProvider(ProcessBuilder processBuilder, Function.FunctionDetails functionDetails) {
        log.info("Configuring process runtime for function {}/{}/{}",
                functionDetails.getTenant(), functionDetails.getNamespace(), functionDetails.getName());
    }

    @Override
    public Type getSecretObjectType() {
        // This tells the Pulsar Functions runtime to pass the value from the secrets map
        // (e.g., the path to the secret) as a String to the provideSecret method.
        return String.class;
    }
}
