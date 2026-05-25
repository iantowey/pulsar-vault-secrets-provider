package com.ostk.pulsar.extensions.secrets;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultException;
import io.github.jopenlibs.vault.api.Logical;
import io.github.jopenlibs.vault.response.LogicalResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VaultSecretsProviderTest {

    @Mock
    private Vault vault;

    @Mock
    private Vault vaultWithRetries;

    @Mock
    private Logical logical;

    @Mock
    private LogicalResponse logicalResponse;

    private VaultSecretsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new VaultSecretsProvider();
        provider.setVault(vault);
        provider.setMaxRetries(0);
        provider.setRetryInterval(0);
    }

    // ---- provideSecret tests ----

    @Test
    void provideSecret_returnsValue_whenKeyExists() throws Exception {
        String functionPath = "secret/myapp";
        String key = "db_password";
        String expected = "s3cret!";

        java.lang.reflect.Field field = VaultSecretsProvider.class.getDeclaredField("functionPath");
        field.setAccessible(true);
        field.set(provider, functionPath);

        when(vault.withRetries(0, 0)).thenReturn(vaultWithRetries);
        when(vaultWithRetries.logical()).thenReturn(logical);
        when(logical.read(functionPath)).thenReturn(logicalResponse);
        when(logicalResponse.getData()).thenReturn(Map.of(key, expected));

        String result = provider.provideSecret(key, "ignored-path");
        assertEquals(expected, result);
    }

    @Test
    void provideSecret_returnsNull_whenKeyNotFound() throws Exception {
        String functionPath = "secret/myapp";

        java.lang.reflect.Field field = VaultSecretsProvider.class.getDeclaredField("functionPath");
        field.setAccessible(true);
        field.set(provider, functionPath);

        when(vault.withRetries(0, 0)).thenReturn(vaultWithRetries);
        when(vaultWithRetries.logical()).thenReturn(logical);
        when(logical.read(functionPath)).thenReturn(logicalResponse);
        when(logicalResponse.getData()).thenReturn(Map.of("other_key", "value"));

        String result = provider.provideSecret("missing_key", "ignored-path");
        assertNull(result);
    }

    @Test
    void provideSecret_returnsNull_whenPathNotFound() throws Exception {
        String functionPath = "secret/nonexistent";

        java.lang.reflect.Field field = VaultSecretsProvider.class.getDeclaredField("functionPath");
        field.setAccessible(true);
        field.set(provider, functionPath);

        when(vault.withRetries(0, 0)).thenReturn(vaultWithRetries);
        when(vaultWithRetries.logical()).thenReturn(logical);
        when(logical.read(functionPath)).thenReturn(logicalResponse);
        when(logicalResponse.getData()).thenReturn(null);

        String result = provider.provideSecret("any_key", "ignored-path");
        assertNull(result);
    }

    @Test
    void provideSecret_returnsNull_whenVaultThrows() throws Exception {
        String functionPath = "secret/myapp";

        java.lang.reflect.Field field = VaultSecretsProvider.class.getDeclaredField("functionPath");
        field.setAccessible(true);
        field.set(provider, functionPath);

        when(vault.withRetries(0, 0)).thenReturn(vaultWithRetries);
        when(vaultWithRetries.logical()).thenReturn(logical);
        when(logical.read(functionPath)).thenThrow(new VaultException("connection refused"));

        String result = provider.provideSecret("key", "ignored-path");
        assertNull(result);
    }

    @Test
    void provideSecret_returnsNull_whenPathIsNull() {
        // functionPath is null by default in the test provider instance
        String result = provider.provideSecret("key", "ignored-path");
        assertNull(result);
    }

    @Test
    void provideSecret_throwsIllegalState_whenNotInitialised() {
        VaultSecretsProvider uninitialised = new VaultSecretsProvider();
        assertThrows(IllegalStateException.class,
                () -> uninitialised.provideSecret("key", "path"));
    }

    // ---- interpolateSecretForValue tests ----

    @Test
    void interpolateSecret_resolvesVaultReference() throws VaultException {
        String path = "secret/myapp";
        String key = "api_key";
        String expected = "abc123";

        when(vault.withRetries(0, 0)).thenReturn(vaultWithRetries);
        when(vaultWithRetries.logical()).thenReturn(logical);
        when(logical.read(path)).thenReturn(logicalResponse);
        when(logicalResponse.getData()).thenReturn(Map.of(key, expected));

        Object result = provider.interpolateSecretForValue("vault:" + path + ":" + key);
        assertEquals(expected, result);
    }

    @Test
    void interpolateSecret_returnsNull_forNonVaultReference() {
        assertNull(provider.interpolateSecretForValue("just-a-plain-value"));
    }

    @Test
    void interpolateSecret_returnsNull_forNullInput() {
        assertNull(provider.interpolateSecretForValue(null));
    }

    @Test
    void interpolateSecret_returnsNull_forPartialPrefix() {
        assertNull(provider.interpolateSecretForValue("vault:only-one-part"));
    }

    // ---- init validation tests ----

    @Test
    void init_throwsOnMissingAddress() {
        Map<String, String> config = new HashMap<>();

        assertThrows(IllegalArgumentException.class, () -> provider.init(config));
    }


    // ---- SECRET_REF_PATTERN tests ----

    @Test
    void pattern_matchesValidReference() {
        var matcher = VaultSecretsProvider.SECRET_REF_PATTERN.matcher("vault:secret/data/app:my_key");
        assertTrue(matcher.matches());
        assertEquals("secret/data/app", matcher.group(1));
        assertEquals("my_key", matcher.group(2));
    }

    @Test
    void pattern_doesNotMatchWithoutPrefix() {
        assertFalse(VaultSecretsProvider.SECRET_REF_PATTERN.matcher("secret/data/app:my_key").matches());
    }

    @Test
    void pattern_doesNotMatchWithoutKey() {
        assertFalse(VaultSecretsProvider.SECRET_REF_PATTERN.matcher("vault:secret/data/app").matches());
    }
}
