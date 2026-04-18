# Perf Smoke

This folder contains a lightweight smoke scenario for quick performance checks and before/after metric comparison.

## Goal

Use one command to:

1. Execute a basic load scenario (`POST /api/auth/login`).
2. Capture selected server metrics from `/actuator/prometheus` before and after the run.
3. Save machine-readable output (`.json`) and human-readable report (`.md`).
4. Optionally compare current run against a baseline summary.

## Prerequisites

- App is running (default: `http://localhost:8081`)
- Keycloak is available
- Endpoint `/actuator/prometheus` is reachable

## Run smoke scenario

```pwsh
pwsh ./ops/perf/perf-smoke.ps1 `
  -BaseUrl http://localhost:8081 `
  -Requests 50 `
  -WarmupRequests 5 `
  -ClientId spring-app `
  -ClientSecret "vYbuDDmT4ouy6vBn6ZzaEPkmaMSHfvab"
```

Outputs are written to `target/perf`:

- `perf-smoke-<timestamp>.json`
- `perf-smoke-<timestamp>.md`

## Compare with baseline

1. Save one run as baseline (for example, before optimization).
2. Run the script again with `-BaselineSummary`.

```pwsh
pwsh ./ops/perf/perf-smoke.ps1 `
  -BaseUrl http://localhost:8081 `
  -Requests 50 `
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
