#!/bin/sh
apk add --no-cache curl jq >/dev/null 2>&1
echo 'Waiting for vault-agent to be ready...'
sleep 8

echo ''
echo '== TEST 1: Read umf/db-creds via agent proxy (no auth) =='
curl -s http://vault-agent:8201/v1/secret/umf/db-creds | jq .

echo ''
echo '== TEST 2: Read umf/api-keys via agent proxy (no auth) =='
curl -s http://vault-agent:8201/v1/secret/umf/api-keys | jq .

echo ''
echo '== TEST 3: Write a new secret to umf/ via agent proxy (no auth) =='
curl -s -X POST \
-H 'Content-Type: application/json' \
-d '{"data": {"conn_string": "redshift://analytics:5439/prod"}}' \
http://vault-agent:8201/v1/secret/umf/redshift | jq .

echo ''
echo '== TEST 4: Read back the new secret via agent proxy (no auth) =='
curl -s http://vault-agent:8201/v1/secret/umf/redshift | jq .

echo ''
echo '== TEST 5: List all keys under umf/ via agent proxy (no auth) =='
curl -s -X LIST http://vault-agent:8201/v1/secret/metaumf | jq .

echo ''
echo '== TEST 6: Read outside umf/ namespace - should FAIL (403) =='
curl -s http://vault-agent:8201/v1/secret/data/other/stuff | jq .

echo ''
echo '== TEST 7: Stolen token from wrong IP - should FAIL (403) =='
TOKEN=$(cat /tmp/vault-agent-token/agent-token)
curl -s -H "X-Vault-Token: $TOKEN" http://vault:8200/v1/secret/umf/db-creds | jq .

echo ''
echo 'Demo complete. Container stays alive for manual testing.'
sleep 3600