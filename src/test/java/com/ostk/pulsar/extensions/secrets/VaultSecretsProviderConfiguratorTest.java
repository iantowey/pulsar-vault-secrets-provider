package com.ostk.pulsar.extensions.secrets;

import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.proto.Function.FunctionDetails;
import org.apache.pulsar.functions.proto.Function.FunctionDetails.ComponentType;
import org.apache.pulsar.functions.proto.Function.FunctionDetails.Runtime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VaultSecretsProviderConfiguratorTest {

    private static final String CLUSTER = "midgard";
    private static final String TENANT = "my-tenant";
    private static final String NAMESPACE = "my-namespace";
    private static final String NAME = "my-component";
    private static final String VAULT_ROOT_PATH = "secret/umf/pulsar/midgard";



    private VaultSecretsProviderConfigurator configurator;

    @BeforeEach
    void setUp() {
        configurator = new VaultSecretsProviderConfigurator();
        Map<String, String> config = new HashMap<>();
        config.put(VaultSecretsProviderConfigurator.CFG_VAULT_ROOT_PATH, VAULT_ROOT_PATH);
        config.put(VaultSecretsProviderConfigurator.CFG_PULSAR_CLUSTER, CLUSTER);
        configurator.init(config);
    }

    // ---- getSecretsProviderConfig: vault path by component type ----

    @Test
    void getSecretsProviderConfig_setsFunctionPath_forFunction() {
        FunctionDetails details = buildFunctionDetails();

        Map<String, String> result = configurator.getSecretsProviderConfig(details);

        String expectedPath = String.format("%s/function/%s/%s/%s",
                VAULT_ROOT_PATH, TENANT, NAMESPACE, NAME);
        assertEquals(expectedPath, result.get(VaultSecretsProviderConfigurator.CFG_VAULT_FUNCTION_PATH));
    }

    @Test
    void getSecretsProviderConfig_setsSourcePath_forSource() {
        FunctionDetails details = buildSourceDetails();

        Map<String, String> result = configurator.getSecretsProviderConfig(details);

        String expectedPath = String.format("%s/source/%s/%s/%s",
                VAULT_ROOT_PATH, TENANT, NAMESPACE, NAME);
        assertEquals(expectedPath, result.get(VaultSecretsProviderConfigurator.CFG_VAULT_FUNCTION_PATH));
    }

    @Test
    void getSecretsProviderConfig_setsSinkPath_forBuiltinSink() {
        FunctionDetails details = buildBuiltinSinkDetails();

        Map<String, String> result = configurator.getSecretsProviderConfig(details);

        String expectedPath = String.format("%s/sink/%s/%s/%s",
                VAULT_ROOT_PATH, TENANT, NAMESPACE, NAME);
        assertEquals(expectedPath, result.get(VaultSecretsProviderConfigurator.CFG_VAULT_FUNCTION_PATH));
    }

    @Test
    void getSecretsProviderConfig_setsSinkPath_forCustomSinkClass() {
        FunctionDetails details = buildCustomClassSinkDetails();

        Map<String, String> result = configurator.getSecretsProviderConfig(details);

        String expectedPath = String.format("%s/sink/%s/%s/%s",
                VAULT_ROOT_PATH, TENANT, NAMESPACE, NAME);
        assertEquals(expectedPath, result.get(VaultSecretsProviderConfigurator.CFG_VAULT_FUNCTION_PATH));
    }


    @Test
    void getSecretsProviderConfig_throwsWhenClusterNotConfigured() {
        VaultSecretsProviderConfigurator noCluster = new VaultSecretsProviderConfigurator();
        Map<String, String> config = new HashMap<>();

        noCluster.init(config);

        FunctionDetails details = buildFunctionDetails();

        assertThrows(IllegalArgumentException.class, () -> noCluster.getSecretsProviderConfig(details));
    }

    // ---- getSecretsProviderClassName: runtime-specific class names ----

    @Test
    void getSecretsProviderClassName_returnsJavaClass_forJavaRuntime() {
        FunctionDetails details = buildDetailsWithRuntime(Runtime.JAVA);

        String className = configurator.getSecretsProviderClassName(details);

        assertEquals(VaultSecretsProvider.class.getCanonicalName(), className);
    }

    @Test
    void getSecretsProviderClassName_returnsPythonModule_forPythonRuntime() {
        FunctionDetails details = buildDetailsWithRuntime(Runtime.PYTHON);

        String className = configurator.getSecretsProviderClassName(details);

        assertEquals("vault_secrets_provider.VaultSecretsProvider", className);
    }

    @Test
    void getSecretsProviderClassName_returnsEmptyString_forGoRuntime() {
        FunctionDetails details = buildDetailsWithRuntime(Runtime.GO);

        String className = configurator.getSecretsProviderClassName(details);

        assertEquals("", className);
    }

    // ---- Helpers: build FunctionDetails for each component type ----

    private FunctionDetails buildDetailsWithRuntime(Runtime runtime) {
        return FunctionDetails.newBuilder()
                .setTenant(TENANT)
                .setNamespace(NAMESPACE)
                .setName(NAME)
                .setComponentType(ComponentType.FUNCTION)
                .setRuntime(runtime)
                .build();
    }

    /**
     * A Pulsar Function: has input specs, sink is PulsarSink or empty.
     */
    private FunctionDetails buildFunctionDetails() {
        return FunctionDetails.newBuilder()
                .setTenant(TENANT)
                .setNamespace(NAMESPACE)
                .setName(NAME)
                .setComponentType(ComponentType.FUNCTION)
                .setClassName("com.example.MyFunction")
                .setSource(Function.SourceSpec.newBuilder()
                        .putInputSpecs("persistent://tenant/ns/input", Function.ConsumerSpec.newBuilder().build()))
                .setSink(Function.SinkSpec.newBuilder()
                        .setTopic("persistent://tenant/ns/output"))
                .build();
    }

    /**
     * A Source connector: no input specs (produces data).
     */
    private FunctionDetails buildSourceDetails() {
        return FunctionDetails.newBuilder()
                .setTenant(TENANT)
                .setNamespace(NAMESPACE)
                .setName(NAME)
                .setComponentType(ComponentType.SOURCE)
                .setSource(Function.SourceSpec.newBuilder()
                        .setClassName("org.apache.pulsar.io.kafka.KafkaSource"))
                .setSink(Function.SinkSpec.newBuilder()
                        .setTopic("persistent://tenant/ns/output"))
                .build();
    }

    /**
     * Source specs but componentType incorrectly set to FUNCTION (the observed bug).
     */
    private FunctionDetails buildSourceDetailsWithWrongComponentType() {
        return FunctionDetails.newBuilder()
                .setTenant(TENANT)
                .setNamespace(NAMESPACE)
                .setName(NAME)
                .setComponentType(ComponentType.FUNCTION)  // <-- wrong
                .setSource(Function.SourceSpec.newBuilder()
                        .setClassName("org.apache.pulsar.io.kafka.KafkaSource"))
                .setSink(Function.SinkSpec.newBuilder()
                        .setTopic("persistent://tenant/ns/output"))
                .build();
    }

    /**
     * A Sink connector using a builtin name.
     */
    private FunctionDetails buildBuiltinSinkDetails() {
        return FunctionDetails.newBuilder()
                .setTenant(TENANT)
                .setNamespace(NAMESPACE)
                .setName(NAME)
                .setComponentType(ComponentType.SINK)
                .setSource(Function.SourceSpec.newBuilder()
                        .putInputSpecs("persistent://tenant/ns/input", Function.ConsumerSpec.newBuilder().build()))
                .setSink(Function.SinkSpec.newBuilder()
                        .setBuiltin("jdbc-postgres"))
                .build();
    }

    /**
     * A Sink connector using a custom class name.
     */
    private FunctionDetails buildCustomClassSinkDetails() {
        return FunctionDetails.newBuilder()
                .setTenant(TENANT)
                .setNamespace(NAMESPACE)
                .setName(NAME)
                .setComponentType(ComponentType.SINK)
                .setSource(Function.SourceSpec.newBuilder()
                        .putInputSpecs("persistent://tenant/ns/input", Function.ConsumerSpec.newBuilder().build()))
                .setSink(Function.SinkSpec.newBuilder()
                        .setClassName("com.example.MyCustomSink"))
                .build();
    }

    /**
     * Sink specs but componentType incorrectly set to FUNCTION (the observed bug).
     */
    private FunctionDetails buildSinkDetailsWithWrongComponentType() {
        return FunctionDetails.newBuilder()
                .setTenant(TENANT)
                .setNamespace(NAMESPACE)
                .setName(NAME)
                .setComponentType(ComponentType.FUNCTION)  // <-- wrong
                .setSource(Function.SourceSpec.newBuilder()
                        .putInputSpecs("persistent://tenant/ns/input", Function.ConsumerSpec.newBuilder().build()))
                .setSink(Function.SinkSpec.newBuilder()
                        .setClassName("com.example.MyCustomSink"))
                .build();
    }
}
