# sign

Sign a plugin JAR with a private key for integrity verification.

## Overview

The `sign` command signs a plugin JAR file so it can be verified during loading. Karandash verifies plugin signatures before execution to prevent loading tampered or unauthorized plugins.

`sign` is a subcommand of `plugin`:

```bash
karandash plugin sign <jar-file> [options]
```

## Arguments

| Argument | Description |
|----------|-------------|
| `jar-file` | Path to the plugin JAR file to sign |

## Options

| Option | Short | Description |
|--------|-------|-------------|
| `--key <path>` | | Path to private key file (RSA, PKCS#8 or PKCS#1 DER) - **required** |
| `--output <path>` | `-o` | Output path for signature file (default: `<jar-file>.sig`) |

## Examples

```bash
# Sign a JAR plugin with default signature path
karandash plugin sign my-plugin-1.0.0.jar --key private.der

# Sign with a custom output path for the signature
karandash plugin sign my-plugin.jar --key private.der --output my-plugin.sig
```

## Key Format

The private key must be an RSA key in DER, either PKCS#8 or PKCS#1 (signatures are RSA-SHA256). To generate a key pair:

```bash
# Generate private key (DER; PKCS#1 under OpenSSL 3, PKCS#8 under LibreSSL)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.der -outform DER

# Extract public key for distribution
openssl pkey -in private.der -inform DER -pubout -out public.pem
```

## See Also

- [plugin](plugin.md) - Install and manage plugins
- [plugin install](plugin.md#install) - Install a signed plugin
