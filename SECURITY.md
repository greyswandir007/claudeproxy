# Security policy

claudeproxy stores provider API keys and OAuth secrets in its database and
 forwards them to upstream providers. Please report vulnerabilities
 privately — do not open a public issue with exploit details.

- **Email:** greyswandir007@gmail.com
- Expect an acknowledgement within a few days; a fix or mitigation plan
  follows depending on severity.

## Scope

- Anything that could leak provider keys, client keys or OAuth tokens.
- Authentication bypasses on `/v1/*` or the dashboard.
- Request-smuggling or injection through the proxying path.

## Non-goals

- Traffic between you and your providers when the proxy is exposed to a
  hostile network without TLS — terminate TLS in front or bind to
  localhost only.
