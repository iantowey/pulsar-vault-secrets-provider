FROM apachepulsar/pulsar-all:3.0.16
COPY target/pulsar-vault-secrets-provider-*-SNAPSHOT.jar /pulsar/lib/
# Python Vault SecretsProvider – placed alongside the built-in secretsprovider.py
# so that Pulsar's util.import_class() can resolve it for Python-runtime functions.
COPY src/main/python/vault_secrets_provider.py /pulsar/instances/python-instance/

# for testing
COPY secrets-demo-1.9.167-SNAPSHOT-java17-jar-with-dependencies.jar /tmp/secrets-demo-1.9.167-SNAPSHOT-java17-jar-with-dependencies.jar
COPY passthrough.py /tmp/passthrough.py
COPY passthrough-go /tmp/passthrough-go