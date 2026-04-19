# Perf Smoke

This folder contains a lightweight smoke scenario for quick performance checks and before/after metric comparison.

## Goal

Use one command to:

1. Execute a basic load scenario (`POST /api/auth/login`).
2. Run warmup first (if configured), then capture baseline metrics.
3. Capture selected server metrics from `/actuator/prometheus` before and after the scenario run.
4. Save machine-readable output (`.json`) and human-readable report (`.md`).
5. Optionally compare current run against a baseline summary.

## Prerequisites

- App is running (default: `http://localhost:8081`)
- Keycloak is available
- Endpoint `/actuator/prometheus` is reachable
- PowerShell 7+ (`pwsh`)

## Run smoke scenario

Set credentials via env vars (recommended):

```pwsh
$env:PERF_SMOKE_USERNAME = "user"
$env:PERF_SMOKE_PASSWORD = "<password>"
$env:PERF_SMOKE_CLIENT_SECRET = "<client-secret>"
```

Optional:
- `PERF_SMOKE_CLIENT_ID` (defaults to `spring-app`)
- `-Insecure` to skip certificate validation for local/self-signed HTTPS endpoints

Then run:

```pwsh
pwsh ./ops/perf/perf-smoke.ps1 `
  -BaseUrl http://localhost:8081 `
  -Requests 5 `
  -WarmupRequests 0 `
  -ClientId spring-app
```

HTTPS example:

```pwsh
pwsh ./ops/perf/perf-smoke.ps1 `
  -BaseUrl https://localhost:8081 `
  -Requests 5 `
  -WarmupRequests 0 `
  -ClientId spring-app `
  -Insecure
```

Rate-limit note:
- Default login rate limit is `5 requests / 60 seconds` per IP (`/api/auth/login`).
- Script defaults are rate-limit-safe: `-Requests 5` and `-WarmupRequests 0`.
- Keep `-Requests` within that window (for example `5` with `-WarmupRequests 0`) unless you temporarily increase/disable the login rate-limit rule.

Outputs are written to `target/perf`:

- `perf-smoke-<timestamp>.json`
- `perf-smoke-<timestamp>.md`

## Compare with baseline

1. Save one run as baseline (for example, before optimization).
2. Run the script again with `-BaselineSummary`.

```pwsh
pwsh ./ops/perf/perf-smoke.ps1 `
  -BaseUrl http://localhost:8081 `
  -Requests 5 `
  -WarmupRequests 0 `
  -BaselineSummary target/perf/perf-smoke-20260418-120000.json
```

Additional output:

- `perf-smoke-comparison-<timestamp>.md`

## Tracked metrics

The script captures and compares:

- `http_server_requests_seconds_count{uri="/api/auth/login",method="POST"}`
- `http_server_requests_seconds_sum{uri="/api/auth/login",method="POST"}`
- `auth_login_total{result="success|failure"}`
- `process_cpu_usage`
- `jvm_memory_used_bytes{area="heap"}`
- `jvm_threads_live_threads`

It also reports client-side throughput and latency (`avg`, `p50`, `p95`, `p99`).
