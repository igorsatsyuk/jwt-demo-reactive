#Requires -Version 7.0

param(
    [string]$BaseUrl = "http://localhost:8081",
    [int]$Requests = 5,
    [int]$WarmupRequests = 0,
    [int]$RequestTimeoutSec = 10,
    [string]$Username = $env:PERF_SMOKE_USERNAME,
    [string]$Password = $env:PERF_SMOKE_PASSWORD,
    [string]$ClientId = $env:PERF_SMOKE_CLIENT_ID,
    [string]$ClientSecret = $env:PERF_SMOKE_CLIENT_SECRET,
    [string]$OutputDir = "target/perf",
    [string]$BaselineSummary = "",
    [switch]$Insecure
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Unescape-PrometheusLabelValue {
    param([string]$EscapedValue)

    if ([string]::IsNullOrEmpty($EscapedValue)) {
        return $EscapedValue
    }

    $builder = [System.Text.StringBuilder]::new()
    $chars = $EscapedValue.ToCharArray()
    for ($i = 0; $i -lt $chars.Length; $i++) {
        $ch = $chars[$i]
        if ($ch -ne '\') {
            [void]$builder.Append($ch)
            continue
        }

        if ($i -eq ($chars.Length - 1)) {
            [void]$builder.Append('\')
            break
        }

        $i++
        $next = $chars[$i]
        switch ($next) {
            '\' { [void]$builder.Append('\') }
            '"' { [void]$builder.Append('"') }
            'n' { [void]$builder.Append("`n") }
            default { [void]$builder.Append($next) }
        }
    }

    return $builder.ToString()
}

function Convert-LabelStringToHashtable {
    param([string]$LabelString)

    $labels = @{}
    if ([string]::IsNullOrWhiteSpace($LabelString)) {
        return $labels
    }

    $pattern = '([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"])*)"'
    foreach ($match in [System.Text.RegularExpressions.Regex]::Matches($LabelString, $pattern)) {
        $key = $match.Groups[1].Value
        $value = Unescape-PrometheusLabelValue -EscapedValue $match.Groups[2].Value
        $labels[$key] = $value
    }

    return $labels
}

function Parse-PrometheusValue {
    param([string]$ValueToken)

    switch ($ValueToken) {
        "NaN" { return [double]::NaN }
        "+Inf" { return [double]::PositiveInfinity }
        "Inf" { return [double]::PositiveInfinity }
        "-Inf" { return [double]::NegativeInfinity }
        default {
            return [double]::Parse($ValueToken, [System.Globalization.CultureInfo]::InvariantCulture)
        }
    }
}

function Get-PrometheusSamples {
    param([string]$PrometheusText)

    $samples = New-Object System.Collections.Generic.List[object]
    if ([string]::IsNullOrWhiteSpace($PrometheusText)) {
        return $samples
    }

    $pattern = '^(?<name>[a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(?<labels>[^}]*)\})?\s+(?<value>[-+]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][-+]?\d+)?|NaN|\+Inf|Inf|-Inf)$'
    foreach ($line in ($PrometheusText -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
            continue
        }

        $match = [System.Text.RegularExpressions.Regex]::Match($line, $pattern)
        if (-not $match.Success) {
            continue
        }

        $samples.Add([pscustomobject]@{
                Name = $match.Groups["name"].Value
                Labels = Convert-LabelStringToHashtable -LabelString $match.Groups["labels"].Value
                Value = Parse-PrometheusValue -ValueToken $match.Groups["value"].Value
            })
    }

    return $samples
}

function Test-LabelsMatch {
    param(
        [hashtable]$SampleLabels,
        [hashtable]$RequiredLabels
    )

    foreach ($key in $RequiredLabels.Keys) {
        if (-not $SampleLabels.ContainsKey($key)) {
            return $false
        }
        if ($SampleLabels[$key] -ne $RequiredLabels[$key]) {
            return $false
        }
    }

    return $true
}

function Get-MetricSumValue {
    param(
        [System.Collections.Generic.List[object]]$Samples,
        [string]$Name,
        [hashtable]$RequiredLabels = @{}
    )

    $sum = 0.0
    foreach ($sample in $Samples) {
        if ($sample.Name -ne $Name) {
            continue
        }
        if (-not (Test-LabelsMatch -SampleLabels $sample.Labels -RequiredLabels $RequiredLabels)) {
            continue
        }
        $sum += [double]$sample.Value
    }
    return $sum
}

function Get-LatencyPercentileFromSorted {
    param(
        [double[]]$SortedValues,
        [double]$Percentile
    )

    if ($SortedValues.Count -eq 0) {
        return 0.0
    }

    $rank = [Math]::Ceiling(($Percentile / 100.0) * $SortedValues.Count) - 1
    $index = [Math]::Max(0, [Math]::Min([int]$rank, $SortedValues.Count - 1))
    return [double]$SortedValues[$index]
}

function Format-Number {
    param(
        [double]$Value,
        [int]$Scale = 3
    )
    return $Value.ToString("F$Scale", [System.Globalization.CultureInfo]::InvariantCulture)
}

function Get-MetricSnapshot {
    param([System.Collections.Generic.List[object]]$Samples)

    return [ordered]@{
        login_http_requests_count = Get-MetricSumValue -Samples $Samples -Name "http_server_requests_seconds_count" -RequiredLabels @{ uri = "/api/auth/login"; method = "POST" }
        login_http_duration_seconds = Get-MetricSumValue -Samples $Samples -Name "http_server_requests_seconds_sum" -RequiredLabels @{ uri = "/api/auth/login"; method = "POST" }
        auth_login_success_total = Get-MetricSumValue -Samples $Samples -Name "auth_login_total" -RequiredLabels @{ result = "success" }
        auth_login_failure_total = Get-MetricSumValue -Samples $Samples -Name "auth_login_total" -RequiredLabels @{ result = "failure" }
        process_cpu_usage = Get-MetricSumValue -Samples $Samples -Name "process_cpu_usage"
        jvm_heap_used_bytes = Get-MetricSumValue -Samples $Samples -Name "jvm_memory_used_bytes" -RequiredLabels @{ area = "heap" }
        jvm_live_threads = Get-MetricSumValue -Samples $Samples -Name "jvm_threads_live_threads"
    }
}

function Get-DiffSnapshot {
    param(
        [System.Collections.IDictionary]$Before,
        [System.Collections.IDictionary]$After
    )

    $diff = [ordered]@{}
    foreach ($key in $After.Keys) {
        $beforeValue = 0.0
        if ($Before.Contains($key)) {
            $beforeValue = [double]$Before[$key]
        }
        $diff[$key] = [double]$After[$key] - $beforeValue
    }
    return $diff
}

function Get-PercentChange {
    param(
        [double]$Baseline,
        [double]$Current
    )

    if ($Baseline -eq 0.0) {
        return $null
    }

    return (($Current - $Baseline) / $Baseline) * 100.0
}

function Get-ObjectPropertyDouble {
    param(
        [object]$Object,
        [string]$PropertyName,
        [double]$DefaultValue = 0.0
    )

    if ($null -eq $Object) {
        return $DefaultValue
    }

    $prop = $Object.PSObject.Properties[$PropertyName]
    if ($null -eq $prop -or $null -eq $prop.Value) {
        return $DefaultValue
    }

    try {
        return [double]$prop.Value
    }
    catch {
        return $DefaultValue
    }
}

function Get-FirstObjectPropertyDouble {
    param(
        [object]$Object,
        [string[]]$PropertyNames,
        [double]$DefaultValue = 0.0
    )

    if ($null -eq $Object -or $null -eq $PropertyNames) {
        return $DefaultValue
    }

    foreach ($propertyName in $PropertyNames) {
        $prop = $Object.PSObject.Properties[$propertyName]
        if ($null -eq $prop -or $null -eq $prop.Value) {
            continue
        }

        try {
            return [double]$prop.Value
        }
        catch {
            continue
        }
    }

    return $DefaultValue
}

function Get-ActuatorPrometheusText {
    param([string]$Url)

    $requestParams = @{
        Uri = $Url
        Method = "Get"
        TimeoutSec = $RequestTimeoutSec
    }

    if ($Insecure.IsPresent) {
        $requestParams["SkipCertificateCheck"] = $true
    }

    return (Invoke-WebRequest @requestParams).Content
}

function Invoke-LoginCall {
    param(
        [string]$Url,
        [string]$BodyJson
    )

    $requestParams = @{
        Uri = $Url
        Method = "Post"
        ContentType = "application/json"
        Body = $BodyJson
        TimeoutSec = $RequestTimeoutSec
        ErrorAction = "Stop"
    }

    if ($Insecure.IsPresent) {
        $requestParams["SkipCertificateCheck"] = $true
    }

    return Invoke-RestMethod @requestParams
}

function Write-SmokeMarkdownReport {
    param(
        [string]$Path,
        [System.Collections.IDictionary]$Summary,
        [System.Collections.IDictionary]$Derived
    )

    $metricRows = @(
        @("login_http_requests_count", $Summary.server.before.login_http_requests_count, $Summary.server.after.login_http_requests_count, $Summary.server.delta.login_http_requests_count),
        @("login_http_duration_seconds", $Summary.server.before.login_http_duration_seconds, $Summary.server.after.login_http_duration_seconds, $Summary.server.delta.login_http_duration_seconds),
        @("auth_login_success_total", $Summary.server.before.auth_login_success_total, $Summary.server.after.auth_login_success_total, $Summary.server.delta.auth_login_success_total),
        @("auth_login_failure_total", $Summary.server.before.auth_login_failure_total, $Summary.server.after.auth_login_failure_total, $Summary.server.delta.auth_login_failure_total),
        @("process_cpu_usage", $Summary.server.before.process_cpu_usage, $Summary.server.after.process_cpu_usage, $Summary.server.delta.process_cpu_usage),
        @("jvm_heap_used_bytes", $Summary.server.before.jvm_heap_used_bytes, $Summary.server.after.jvm_heap_used_bytes, $Summary.server.delta.jvm_heap_used_bytes),
        @("jvm_live_threads", $Summary.server.before.jvm_live_threads, $Summary.server.after.jvm_live_threads, $Summary.server.delta.jvm_live_threads)
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Perf Smoke Report")
    $lines.Add("")
    $lines.Add("- Generated (UTC): $($Summary.generatedAtUtc)")
    $lines.Add("- Base URL: $($Summary.baseUrl)")
    $lines.Add("- Scenario: POST /api/auth/login")
    $lines.Add("")
    $lines.Add("## Client-side result")
    $lines.Add("")
    $lines.Add("| Metric | Value |")
    $lines.Add("|---|---|")
    $lines.Add("| Requests | $($Summary.client.requests) |")
    $lines.Add("| Success | $($Summary.client.success) |")
    $lines.Add("| Failed | $($Summary.client.failed) |")
    $lines.Add("| Total duration, s | $(Format-Number -Value $Summary.client.durationSeconds -Scale 3) |")
    $lines.Add("| Throughput, req/s | $(Format-Number -Value $Summary.client.throughputRps -Scale 2) |")
    $lines.Add("| Latency avg, ms | $(Format-Number -Value $Summary.client.latency.avgMs -Scale 2) |")
    $lines.Add("| Latency p50, ms | $(Format-Number -Value $Summary.client.latency.p50Ms -Scale 2) |")
    $lines.Add("| Latency p95, ms | $(Format-Number -Value $Summary.client.latency.p95Ms -Scale 2) |")
    $lines.Add("| Latency p99, ms | $(Format-Number -Value $Summary.client.latency.p99Ms -Scale 2) |")
    $lines.Add("")
    $lines.Add("## Server metrics (before/after)")
    $lines.Add("")
    $lines.Add("| Metric | Before | After | Delta |")
    $lines.Add("|---|---:|---:|---:|")
    foreach ($row in $metricRows) {
        $lines.Add("| $($row[0]) | $(Format-Number -Value $row[1] -Scale 6) | $(Format-Number -Value $row[2] -Scale 6) | $(Format-Number -Value $row[3] -Scale 6) |")
    }
    $lines.Add("")
    $lines.Add("## Derived server-side values")
    $lines.Add("")
    $lines.Add("| Metric | Value |")
    $lines.Add("|---|---:|")
    $lines.Add("| login_http_avg_latency_scenario_ms | $(Format-Number -Value $Derived.login_http_avg_latency_scenario_ms -Scale 2) |")
    $lines.Add("| login_http_count_delta | $(Format-Number -Value $Derived.login_http_count_delta -Scale 0) |")
    $lines.Add("| auth_login_success_delta | $(Format-Number -Value $Derived.auth_login_success_delta -Scale 0) |")
    $lines.Add("| auth_login_failure_delta | $(Format-Number -Value $Derived.auth_login_failure_delta -Scale 0) |")
    $lines.Add("")

    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

function Write-BaselineComparisonReport {
    param(
        [string]$Path,
        [System.Collections.IDictionary]$Current,
        [pscustomobject]$Baseline,
        [string]$BaselinePath
    )

    $baselineThroughput = Get-ObjectPropertyDouble -Object $Baseline.client -PropertyName "throughputRps"
    $baselineP95 = if ($null -ne $Baseline.client) { Get-ObjectPropertyDouble -Object $Baseline.client.latency -PropertyName "p95Ms" } else { 0.0 }
    $baselineServerAvg = if ($null -ne $Baseline.server) {
        Get-FirstObjectPropertyDouble -Object $Baseline.server.derived -PropertyNames @(
                "login_http_avg_latency_scenario_ms",
                "login_http_avg_latency_after_ms"
        )
    } else { 0.0 }
    $baselineSuccessDelta = if ($null -ne $Baseline.server) { Get-ObjectPropertyDouble -Object $Baseline.server.derived -PropertyName "auth_login_success_delta" } else { 0.0 }
    $baselineFailureDelta = if ($null -ne $Baseline.server) { Get-ObjectPropertyDouble -Object $Baseline.server.derived -PropertyName "auth_login_failure_delta" } else { 0.0 }

    $currentThroughput = [double]$Current.client.throughputRps
    $currentP95 = [double]$Current.client.latency.p95Ms
    $currentServerAvg = Get-FirstObjectPropertyDouble -Object $Current.server.derived -PropertyNames @(
            "login_http_avg_latency_scenario_ms",
            "login_http_avg_latency_after_ms"
    )
    $currentSuccessDelta = [double]$Current.server.derived.auth_login_success_delta
    $currentFailureDelta = [double]$Current.server.derived.auth_login_failure_delta

    $throughputPct = Get-PercentChange -Baseline $baselineThroughput -Current $currentThroughput
    $p95Pct = Get-PercentChange -Baseline $baselineP95 -Current $currentP95
    $serverAvgPct = Get-PercentChange -Baseline $baselineServerAvg -Current $currentServerAvg

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Perf Smoke Comparison")
    $lines.Add("")
    $lines.Add("- Baseline file: $BaselinePath")
    $lines.Add("- Current run: $($Current.generatedAtUtc)")
    $lines.Add("")
    $lines.Add("| Metric | Baseline | Current | Delta | Delta % |")
    $lines.Add("|---|---:|---:|---:|---:|")
    $lines.Add("| throughput_req_per_sec | $(Format-Number -Value $baselineThroughput -Scale 2) | $(Format-Number -Value $currentThroughput -Scale 2) | $(Format-Number -Value ($currentThroughput - $baselineThroughput) -Scale 2) | $(if ($null -eq $throughputPct) {'n/a'} else { (Format-Number -Value $throughputPct -Scale 2) + '%' }) |")
    $lines.Add("| client_latency_p95_ms | $(Format-Number -Value $baselineP95 -Scale 2) | $(Format-Number -Value $currentP95 -Scale 2) | $(Format-Number -Value ($currentP95 - $baselineP95) -Scale 2) | $(if ($null -eq $p95Pct) {'n/a'} else { (Format-Number -Value $p95Pct -Scale 2) + '%' }) |")
    $lines.Add("| server_login_avg_latency_scenario_ms | $(Format-Number -Value $baselineServerAvg -Scale 2) | $(Format-Number -Value $currentServerAvg -Scale 2) | $(Format-Number -Value ($currentServerAvg - $baselineServerAvg) -Scale 2) | $(if ($null -eq $serverAvgPct) {'n/a'} else { (Format-Number -Value $serverAvgPct -Scale 2) + '%' }) |")
    $lines.Add("| auth_login_success_delta | $(Format-Number -Value $baselineSuccessDelta -Scale 0) | $(Format-Number -Value $currentSuccessDelta -Scale 0) | $(Format-Number -Value ($currentSuccessDelta - $baselineSuccessDelta) -Scale 0) | n/a |")
    $lines.Add("| auth_login_failure_delta | $(Format-Number -Value $baselineFailureDelta -Scale 0) | $(Format-Number -Value $currentFailureDelta -Scale 0) | $(Format-Number -Value ($currentFailureDelta - $baselineFailureDelta) -Scale 0) | n/a |")
    $lines.Add("")

    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

if ($Requests -le 0) {
    throw "Requests must be > 0."
}
if ($WarmupRequests -lt 0) {
    throw "WarmupRequests must be >= 0."
}
if ([string]::IsNullOrWhiteSpace($ClientId)) {
    $ClientId = "spring-app"
}

$missingCredentials = New-Object System.Collections.Generic.List[string]
if ([string]::IsNullOrWhiteSpace($Username)) {
    [void]$missingCredentials.Add("Username")
}
if ([string]::IsNullOrWhiteSpace($Password)) {
    [void]$missingCredentials.Add("Password")
}
if ([string]::IsNullOrWhiteSpace($ClientSecret)) {
    [void]$missingCredentials.Add("ClientSecret")
}
if ($missingCredentials.Count -gt 0) {
    throw "Missing required credentials: $($missingCredentials -join ', '). Provide parameters or PERF_SMOKE_USERNAME/PERF_SMOKE_PASSWORD/PERF_SMOKE_CLIENT_SECRET env vars."
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

$normalizedBaseUrl = $BaseUrl.TrimEnd('/')
$loginUrl = "$normalizedBaseUrl/api/auth/login"
$prometheusUrl = "$normalizedBaseUrl/actuator/prometheus"
$loginBody = @{
    username = $Username
    password = $Password
    clientId = $ClientId
    clientSecret = $ClientSecret
} | ConvertTo-Json -Compress

if ($WarmupRequests -gt 0) {
    Write-Host "Warmup: $WarmupRequests requests"
    for ($i = 1; $i -le $WarmupRequests; $i++) {
        try {
            [void](Invoke-LoginCall -Url $loginUrl -BodyJson $loginBody)
        }
        catch {
            # Warmup failures are expected in some local setups, keep running.
        }
    }
}

Write-Host "Collecting scenario baseline metrics from $prometheusUrl ..."
$beforeMetricsText = Get-ActuatorPrometheusText -Url $prometheusUrl
$beforeSamples = Get-PrometheusSamples -PrometheusText $beforeMetricsText
$beforeSnapshot = Get-MetricSnapshot -Samples $beforeSamples

Write-Host "Executing smoke scenario: $Requests requests to $loginUrl ..."
$latencies = New-Object System.Collections.Generic.List[double]
$successCount = 0
$failedCount = 0
$scenarioStopwatch = [System.Diagnostics.Stopwatch]::StartNew()

for ($i = 1; $i -le $Requests; $i++) {
    $requestStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-LoginCall -Url $loginUrl -BodyJson $loginBody
        if ($null -ne $response -and $null -ne $response.code -and [int]$response.code -eq 0) {
            $successCount++
        }
        else {
            $failedCount++
        }
    }
    catch {
        $failedCount++
    }
    finally {
        $requestStopwatch.Stop()
        $latencies.Add($requestStopwatch.Elapsed.TotalMilliseconds)
    }
}

$scenarioStopwatch.Stop()

Write-Host "Collecting post-run metrics from $prometheusUrl ..."
$afterMetricsText = Get-ActuatorPrometheusText -Url $prometheusUrl
$afterSamples = Get-PrometheusSamples -PrometheusText $afterMetricsText
$afterSnapshot = Get-MetricSnapshot -Samples $afterSamples
$deltaSnapshot = Get-DiffSnapshot -Before $beforeSnapshot -After $afterSnapshot

$latenciesArray = $latencies.ToArray()
$sortedLatencies = [double[]]($latenciesArray | Sort-Object)
$durationSeconds = [Math]::Max($scenarioStopwatch.Elapsed.TotalSeconds, 0.000001)
$throughput = $Requests / $durationSeconds
$avgLatencyMs = ($latenciesArray | Measure-Object -Average).Average
$minLatencyMs = ($latenciesArray | Measure-Object -Minimum).Minimum
$maxLatencyMs = ($latenciesArray | Measure-Object -Maximum).Maximum
$p50LatencyMs = Get-LatencyPercentileFromSorted -SortedValues $sortedLatencies -Percentile 50
$p95LatencyMs = Get-LatencyPercentileFromSorted -SortedValues $sortedLatencies -Percentile 95
$p99LatencyMs = Get-LatencyPercentileFromSorted -SortedValues $sortedLatencies -Percentile 99

$deltaCount = [double]$deltaSnapshot.login_http_requests_count
$deltaDurationSeconds = [double]$deltaSnapshot.login_http_duration_seconds
$deltaAvgMs = if ($deltaCount -gt 0.0) { ($deltaDurationSeconds / $deltaCount) * 1000.0 } else { 0.0 }

$derived = [ordered]@{
    login_http_avg_latency_scenario_ms = $deltaAvgMs
    login_http_count_delta = $deltaCount
    auth_login_success_delta = [double]$deltaSnapshot.auth_login_success_total
    auth_login_failure_delta = [double]$deltaSnapshot.auth_login_failure_total
}

$summary = [ordered]@{
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
    baseUrl = $BaseUrl
    scenario = [ordered]@{
        endpoint = "/api/auth/login"
        requests = $Requests
        warmupRequests = $WarmupRequests
        requestTimeoutSec = $RequestTimeoutSec
        clientId = $ClientId
    }
    client = [ordered]@{
        requests = $Requests
        success = $successCount
        failed = $failedCount
        durationSeconds = $durationSeconds
        throughputRps = $throughput
        latency = [ordered]@{
            minMs = $minLatencyMs
            maxMs = $maxLatencyMs
            avgMs = $avgLatencyMs
            p50Ms = $p50LatencyMs
            p95Ms = $p95LatencyMs
            p99Ms = $p99LatencyMs
        }
    }
    server = [ordered]@{
        before = $beforeSnapshot
        after = $afterSnapshot
        delta = $deltaSnapshot
        derived = $derived
    }
}

$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
$summaryJsonPath = Join-Path $OutputDir "perf-smoke-$timestamp.json"
$summaryMdPath = Join-Path $OutputDir "perf-smoke-$timestamp.md"

$summary | ConvertTo-Json -Depth 10 | Set-Content -Path $summaryJsonPath -Encoding UTF8
Write-SmokeMarkdownReport -Path $summaryMdPath -Summary $summary -Derived $derived

Write-Host "Smoke summary JSON: $summaryJsonPath"
Write-Host "Smoke summary MD:   $summaryMdPath"

if (-not [string]::IsNullOrWhiteSpace($BaselineSummary)) {
    if (-not (Test-Path $BaselineSummary)) {
        throw "BaselineSummary file not found: $BaselineSummary"
    }

    $baseline = Get-Content -Raw -Path $BaselineSummary | ConvertFrom-Json
    $comparisonPath = Join-Path $OutputDir "perf-smoke-comparison-$timestamp.md"
    Write-BaselineComparisonReport -Path $comparisonPath -Current $summary -Baseline $baseline -BaselinePath $BaselineSummary
    Write-Host "Baseline comparison MD: $comparisonPath"
}
