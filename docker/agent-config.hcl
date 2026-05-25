pid_file = "/tmp/vault-agent.pid"

vault {
  address = "http://vault:8200"
  tls_skip_verify = "true"
}

auto_auth {
  method "approle" {
    namespace = "dpo"
    mount_path = "auth/approle"
    config = {
      role_id_file_path = "/shared/role_id"
    }
  }

  sink "file" {
    config = {
      path = "/tmp/vault-agent-token/agent-token"
      mode = 0644
    }
  }
}

listener "tcp" {
  address     = "0.0.0.0:8201"
  tls_disable = true
}

cache {
  use_auto_auth_token = true
}
