
### Vault store for Pulsar secrets

#### Overview

The generated artifact `./target/pulsar-vault-secrets-provider-*.jar` needs to by copied to the `pulsar-functions-worker` container and added to the jvm classpath (e.g PULSAR_EXTRA_CLASSPATH)

The `functions_worker.yml` needs to be configured to use the secrets provider with appropriate config.

#### Links


#### Demo

```shell
## start up take down env
mci_java17 # alias for ``sdk use java 17.0.16-tem && mvn clean install
docker-compose -f docker-compose.yaml down --remove-orphans -v  && ds image rm pulsar-vault-secrets-provider_pulsar
ds rm demo-app
ds rm vault-setup
ds rm vault-agent
ds rm vault-server
ds rm pulsar
docker-compose -f docker-compose.yaml up -d


# check functions-worker config with secrets provider class and config configured
ds exec pulsar cat conf/functions_worker.yml | grep -A 12 secretsProviderConfiguratorClassName

## view details of vault-agent configured for CIDR based auth with vault master for a specific AppRole and Policy
ds logs vault-setup # app which runs script docker/vault-setup.sh

## verify GET/POST secrets unauthenticated only works from vault-agent and not from any other host
ds logs demo-app # app which runs script docker/demo.sh

# test secret GET/POST - unauthenicated request to vault agent from the pulsar host
ds exec pulsar curl -s --request POST \
                 --header "Content-Type: application/json" \
                 --data '{"k1": "midgard","k2": "salesforce","k3": "braze"}' \
                 http://vault-agent:8201/v1/secret/umf/pulsar/midgard/function/public/default/secrets-demo-func

ds exec pulsar curl -s --request POST \
                 --header "Content-Type: application/json" \
                 --data '{"k1": "midgard-py","k2": "salesforce-py","k3": "braze-py"}' \
                 http://vault-agent:8201/v1/secret/umf/pulsar/midgard/function/public/default/secrets-demo-func-py

ds exec pulsar curl -s --request POST \
                 --header "Content-Type: application/json" \
                 --data '{"k1": "midgard","k2": "salesforce","k3": "braze"}' \
                 http://vault-agent:8201/v1/secret/umf/pulsar/midgard/source/public/default/secrets-demo-source

ds exec pulsar curl -s --request POST \
                 --header "Content-Type: application/json" \
                 --data '{"k1": "midgard","k2": "salesforce","k3": "braze"}' \
                 http://vault-agent:8201/v1/secret/umf/pulsar/midgard/sink/public/default/secrets-demo-sink


ds exec pulsar curl -s http://vault-agent:8201/v1/secret/umf/pulsar/midgard/function/public/default/secrets-demo-func | jq .

#demo deploying
# 
# https://git.acme.com/projects/DEDEV/repos/jvm-func-pantry/browse/functions/secrets-function-demo/src/main/java/com/acme/dataeng/FuncPantry/FunctionWithSecrets.java
# 
#
# N.B the --secrets config keys are required, the value is informational only ; function secrts oath is set in src/main/java/com/acme/pulsar/extensions/secrets/VaultSecretsProviderConfigurator.java line 41 called by the functions worker and passed as config to the process cli 
#

ds exec pulsar bin/pulsar-admin functions create \
  --py /tmp/passthrough.py \
  --classname passthrough.SecretReaderFunction \
  --tenant public \
  --namespace default \
  --name secrets-demo-func-py \
  --inputs persistent://public/default/input-topic-py \
  --output persistent://public/default/output-topic-py \
  --user-config '{"c1":"v1", "c2":"v2"}' \
  --secrets '{"k1":"secret/umf/pulsar/midgard/function/public/default/secrets-demo-func@k1", "k2":"secret/umf/pulsar/midgard/function/public/default/secrets-demo-func@k2", "k3":"secret/umf/pulsar/midgard/function/public/default/secrets-demo-func@k3"}'

docker exec pulsar bin/pulsar-admin functions create \
  --tenant public \
  --namespace default \
  --name secrets-demo-func-go \
  --go /tmp/passthrough-go \
  --inputs persistent://public/default/go-in \
  --output persistent://public/default/go-out \
  --processing-guarantees ATLEAST_ONCE 


ds exec pulsar bin/pulsar-admin functions create \
  --jar /tmp/secrets-demo-1.9.167-SNAPSHOT-java17-jar-with-dependencies.jar \
  --classname com.acme.dataeng.FuncPantry.FunctionWithSecrets \
  --tenant public \
  --namespace default \
  --name secrets-demo-func \
  --inputs persistent://public/default/input-topic \
  --output persistent://public/default/output-topic \
  --user-config '{"c1":"v1", "c2":"v2"}' \
  --secrets '{"k1":"secret/umf/pulsar/midgard/function/public/default/secrets-demo-func@k1", "k2":"secret/umf/pulsar/midgard/function/public/default/secrets-demo-func@k2", "k3":"secret/umf/pulsar/midgard/function/public/default/secrets-demo-func@k3"}'

ds exec pulsar bin/pulsar-admin sources create \
  --archive /tmp/secrets-demo-1.9.167-SNAPSHOT-java17-jar-with-dependencies.jar \
  --className com.acme.dataeng.FuncPantry.SourceWithSecrets \
  --tenant public \
  --namespace default \
  --name secrets-demo-source \
  --destination-topic-name persistent://public/default/output-topic \
  --source-config '{"c1":"v1", "c2":"v2"}' \
  --secrets '{"k1":"secret/umf/pulsar/midgard/source/public/default/secrets-demo-source@k1", "k2":"secret/umf/pulsar/midgard/source/public/default/secrets-demo-source@k2", "k3":"secret/umf/pulsar/midgard/source/public/default/secrets-demo-source@k3"}'

ds exec pulsar bin/pulsar-admin sinks create \
  --archive /tmp/secrets-demo-1.9.167-SNAPSHOT-java17-jar-with-dependencies.jar \
  --className com.acme.dataeng.FuncPantry.SinkWithSecrets \
  --tenant public \
  --namespace default \
  --name secrets-demo-sink \
  --inputs persistent://public/default/input-topic \
  --sink-config '{"c1":"v1", "c2":"v2"}' \
  --secrets '{"k1":"secret/umf/pulsar/midgard/sink/public/default/secrets-demo-sink@k1", "k2":"secret/umf/pulsar/midgard/sink/public/default/secrets-demo-sink@k2", "k3":"secret/umf/pulsar/midgard/sink/public/default/secrets-demo-sink@k3"}'

ds exec -it pulsar tail logs/functions/public/default/secrets-demo-func/secrets-demo-func-0.log -f
ds exec -it pulsar tail logs/functions/public/default/secrets-demo-func-py/secrets-demo-func-py-0.log -f
## there is no log log file ??
ds exec -it pulsar tail logs/functions/public/default/secrets-demo-func-go/secrets-demo-func-go-0.log -f
# go passthrough comsumes ok
docker exec pulsar bin/pulsar-admin functions status   --tenant public   --namespace default   --name secrets-demo-func-go 
 
ds exec -it pulsar tail logs/functions/public/default/secrets-demo-source/secrets-demo-source-0.log -f

ds exec -it pulsar tail logs/functions/public/default/secrets-demo-sink/secrets-demo-sink-0.log -f

ds exec pulsar bin/pulsar-client produce persistent://public/default/input-topic -m "a message"
ds exec pulsar bin/pulsar-client produce persistent://public/default/input-topic-py -m "a message"
ds exec pulsar bin/pulsar-client produce persistent://public/default/go-in -m "a message"

ds exec pulsar bin/pulsar-admin functions status \
  --tenant public \
  --namespace default \
  --name secrets-demo-func

ds exec pulsar bin/pulsar-admin functions delete \
  --tenant public \
  --namespace default \
  --name secrets-demo-func

ds exec pulsar bin/pulsar-admin functions get \
  --tenant public \
  --namespace default \
  --name secrets-demo-func


```

