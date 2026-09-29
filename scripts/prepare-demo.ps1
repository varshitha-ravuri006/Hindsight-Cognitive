<#
  Prepare the live demo, ideally the evening before (Windows PowerShell twin of scripts/prepare-demo):
    1. make sure Vishwas is running against the clean demo bank (starts it if nothing is listening),
    2. reset the demo database and memory bank,
    3. load the four months of history and wait until Hindsight has consolidated it,
    4. print READY with the memory and observation counts and how long each stage took.
  Keeps the PC awake while it runs.

  Usage:  powershell -ExecutionPolicy Bypass -File scripts\prepare-demo.ps1 [-Url http://localhost:8080] [-Bank vishwas-demo] [-NoStart]
#>
param(
    [string]$Url = "http://localhost:8080",
    [string]$Bank = "vishwas-demo",
    [switch]$NoStart
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

Add-Type -Namespace Win32 -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);'
[void][Win32.Power]::SetThreadExecutionState([uint32]"0x80000001")   # ES_CONTINUOUS | ES_SYSTEM_REQUIRED: no sleep while running

function Say([string]$m) { Write-Host ("{0}  {1}" -f (Get-Date -Format HH:mm:ss), $m) }
function Fail([string]$m) { Say "FAILED: $m"; [void][Win32.Power]::SetThreadExecutionState([uint32]"0x80000000"); exit 1 }
function Get-Json([string]$path, [int]$timeout = 30) { Invoke-RestMethod -Uri ($Url + $path) -TimeoutSec $timeout }
function Post-Json([string]$path, [int]$timeout = 60) { Invoke-RestMethod -Method Post -Uri ($Url + $path) -TimeoutSec $timeout }
function Alive { try { [void](Get-Json "/api/health/live" 5); $true } catch { $false } }

try {
    # ------------------------------------------------------------ 1. server on the demo bank
    if (-not (Alive)) {
        if ($NoStart) { Fail "nothing answers at $Url (start Vishwas with HINDSIGHT_BANK_ID=$Bank)" }
        if (-not (Test-Path "target\vishwas.jar")) { Say "Building target\vishwas.jar"; & mvn -B -q package -DskipTests; if ($LASTEXITCODE) { Fail "build failed" } }
        New-Item -ItemType Directory -Force data | Out-Null
        $port = ([Uri]$Url).Port
        Say "Starting Vishwas on port $port with HINDSIGHT_BANK_ID=$Bank (log: data\prepare-demo-server.log)"
        $env:HINDSIGHT_BANK_ID = $Bank
        Start-Process -FilePath "java" -ArgumentList "-jar", "target\vishwas.jar", "--server.port=$port" -WindowStyle Hidden `
            -RedirectStandardOutput "data\prepare-demo-server.log" -RedirectStandardError "data\prepare-demo-server.err.log"
        for ($i = 0; $i -lt 90 -and -not (Alive); $i++) { Start-Sleep 2 }
        if (-not (Alive)) { Fail "Vishwas did not start; see data\prepare-demo-server.log" }
    }
    for ($i = 0; $i -lt 30; $i++) { $status = Get-Json "/api/status" 15; if ($status.memory -ne "CONNECTING") { break }; Start-Sleep 2 }
    if ($status.bankId -ne $Bank) { Fail "Vishwas is running with bank '$($status.bankId)'. Restart it with HINDSIGHT_BANK_ID=$Bank." }
    if ($status.memory -ne "READY") { Fail "memory is $($status.memory): $($status.memoryError)" }
    if ($status.baseline -ne "READY") { Say "Warning: the Groq baseline is not configured; the grey column will show textbook actions." }
    Say "Vishwas at $Url is using bank $Bank; memory READY"

    # ------------------------------------------------------------ 2. reset
    $reset = Post-Json "/api/demo/reset" 240
    if (-not $reset.memoryCleared) { Fail "reset could not clear memory: $($reset.message)" }
    Say "Database and bank $Bank cleared"

    # ------------------------------------------------------------ 3. history, then consolidation
    $since = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    $t0 = Get-Date
    [void](Post-Json "/api/history/load" 30)
    $last = ""
    while ($true) {
        try { $h = Get-Json "/api/history/status" } catch { Start-Sleep 10; continue }
        $line = "{0}  {1}/{2}" -f $h.memory.stage, $h.memory.itemsSent, $h.memory.itemsTotal
        if ($line -ne $last) { Say "History: $line"; $last = $line }
        if ($h.memoryLoaded) { break }
        if ($h.error) { Fail "history load: $($h.error)" }
        if ($h.memory.error) { Fail "history load: $($h.memory.error)" }
        if (((Get-Date) - $t0).TotalMinutes -gt 45) { Fail "history load took more than 45 minutes" }
        Start-Sleep 10
    }
    $t1 = Get-Date
    Say ("History remembered in {0:N0} s; waiting for consolidation to settle" -f ($t1 - $t0).TotalSeconds)
    while ($true) {
        try { $s = Get-Json "/api/memory/settle?since=$since" } catch { Start-Sleep 15; continue }
        if ($s.stage -eq "SETTLED") { break }
        if (((Get-Date) - $t1).TotalMinutes -gt 25) { Fail "consolidation did not settle within 25 minutes (last stage $($s.stage))" }
        Start-Sleep 15
    }
    $t2 = Get-Date

    $status = Get-Json "/api/status"
    Write-Host ""
    Write-Host ("READY  bank={0}  memories={1}  observations={2}" -f $Bank, $status.memories, $status.observations)
    Write-Host ("       history load {0:N0} s, consolidation settled after a further {1:N0} s (total {2:N0} s)" -f ($t1 - $t0).TotalSeconds, ($t2 - $t1).TotalSeconds, ($t2 - $t0).TotalSeconds)
    Write-Host ("       {0}" -f $status.history.summary.line)
    Write-Host "       Open $Url . Steps 2 to 4 run live. If you stop the server, restart it with HINDSIGHT_BANK_ID=$Bank."
}
finally {
    [void][Win32.Power]::SetThreadExecutionState([uint32]"0x80000000")
}
