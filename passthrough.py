from pulsar import Function

class SecretReaderFunction(Function):
    def process(self, input_item, context):
        """
        Reads two secrets (k1 and k2) and prints them.
        """
        # Retrieve secrets using the context object
        secret_k1 = context.get_secret("k1")
        secret_k2 = context.get_secret("k2")
        secret_k3 = context.get_secret("k3")

        # Log/Print the results
        # Note: In production, printing secrets to logs is a security risk.
        print(f"Secret k1: {secret_k1}")
        print(f"Secret k2: {secret_k2}")
        print(f"Secret k2: {secret_k3}")

        return input_item
