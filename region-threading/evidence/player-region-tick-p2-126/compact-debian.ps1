# Run from Windows only after approval to stop the current Debian/WSL session.
# Native WSL 3.0.1 --compact stops this distribution before compacting its VHDX.
param([ValidateRange(0, 3600)][int]$DelaySeconds = 0)
$ErrorActionPreference = 'Stop'
$resultDirectory = 'D:\WSL\Debian\forgia-cleanup-20261117'
New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null
Start-Transcript -Path (Join-Path $resultDirectory 'compact-debian.log') -Append
$result = [ordered]@{
    StartedAt = (Get-Date).ToString('o')
    Distribution = 'Debian'
    Vhd = 'D:\WSL\Debian\ext4.vhdx'
    BeforeFreeBytes = (Get-PSDrive D).Free
    BeforeVhdLength = (Get-Item 'D:\WSL\Debian\ext4.vhdx').Length
    Status = 'started'
}
try {
    if ($DelaySeconds -gt 0) { Start-Sleep -Seconds $DelaySeconds }
    & "$env:WINDIR\System32\wsl.exe" --manage Debian --compact
    $result.NativeExit = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw "Native WSL compaction exited $LASTEXITCODE" }
    $result.Status = 'completed'
} catch {
    $result.Status = 'failed'
    $result.Error = $_.Exception.Message
} finally {
    $result.AfterFreeBytes = (Get-PSDrive D).Free
    $result.ReclaimedHostBytes = $result.AfterFreeBytes - $result.BeforeFreeBytes
    $result.AfterVhdLength = (Get-Item 'D:\WSL\Debian\ext4.vhdx').Length
    $result.FinishedAt = (Get-Date).ToString('o')
    $result | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $resultDirectory 'compact-result.json')
    $result | ConvertTo-Json -Depth 5 | Write-Host
    Stop-Transcript
}
if ($result.Status -ne 'completed') { exit 1 }
