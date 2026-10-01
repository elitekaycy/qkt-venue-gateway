# Security Policy

## Reporting a vulnerability

Email dicksonanyaele1234@gmail.com with "qkt-venue-gateway security" in the subject.

We aim to acknowledge within 7 days and provide a fix or mitigation timeline within 14 days. Please do
not file public GitHub issues for security reports.

## Scope

In scope:
- Authentication bypass: a request served without a valid role token, or a trader token flipping the kill switch
- Credential leakage: venue credentials or tokens in logs, error messages, the journal or any response
- Order handling that can place, change or resend an order nobody asked for, or let new exposure past an
  engaged kill switch
- Code execution, deserialization or path traversal in the host, the config loader or plugin loading

Out of scope:
- Losses from a strategy's own decisions
- Denial of service by a client holding a valid token
- Issues in transitive dependencies that do not affect the gateway's behaviour

## Running it safely

- Bind to localhost or a private network; put TLS in front when the network is shared.
- Give venue API keys read and trade scopes only, never withdrawal.
- Keep tokens and credentials out of config files (`env:` or `file:` references).

## Supported versions

Pre-1.0: only the latest release receives security fixes.
