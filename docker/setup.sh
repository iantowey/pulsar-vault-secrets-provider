#!/bin/sh
set -e

export VAULT_ADDR="http://vault:8200"
export VAULT_TOKEN="root"

echo "==> Waiting for Vault to be ready..."
until vault status >/dev/null 2>&1; do sleep 1; done
echo "==> Vault is ready."

# ── 1. Write demo secrets under the umf namespace ──
echo "==> Writing demo secrets to secret/umf/ ..."
vault kv put secret/umf/db-creds host=db.internal user=etl_svc password=s3cret
vault kv put secret/umf/api-keys snowflake=sf_abc123 dbt_cloud=dbt_xyz789

# ── 2. Create a read/write policy scoped to umf ──
echo "==> Creating policy 'umf-policy' ..."
vault policy write umf-policy - <<'EOF1'
path "secret/umf/*" {
  capabilities = ["read", "create", "update"]
}
EOF1

# ── 3. Enable AppRole auth method ──
echo "==> Enabling AppRole auth method ..."
vault auth enable approle 2>/dev/null || echo "    (approle already enabled)"

# ── 4. Create the role ──
#   bind_secret_id=false   → no secret_id required to log in
#   token_bound_cidrs      → tokens can ONLY be used from the vault-agent's IP
echo "==> Creating AppRole role 'umf-role' ..."
vault write auth/approle/role/umf-role \
  bind_secret_id=false \
  token_bound_cidrs="172.20.0.20/32" \
  token_policies="umf-policy" \
  token_ttl=1h \
  token_max_ttl=4h

# ── 5. Fetch the role_id and write it to the shared volume ──
ROLE_ID=$(vault read -field=role_id auth/approle/role/umf-role/role-id)
echo "$ROLE_ID" > /shared/role_id
echo "==> Role ID written to /shared/role_id: ${ROLE_ID}"

echo "==> Setup complete!"