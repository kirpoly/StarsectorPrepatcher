$ErrorActionPreference = 'Stop'

$modRoot = (Resolve-Path $PSScriptRoot).Path
$modFolder = Split-Path $modRoot -Leaf
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$vmparams = Join-Path $gameRoot 'vmparams'
$agentArgs = @(
    "-javaagent:../mods/$modFolder/agent/StarsectorPrepatcherAgent.jar",
    "-javaagent:../mods/$modFolder/agent/StarsectorPrepatcherHyperspaceAgent.jar"
) | Select-Object -Unique

if (-not (Test-Path -LiteralPath $vmparams -PathType Leaf)) {
    throw "vmparams not found: $vmparams"
}
$content = [System.IO.File]::ReadAllText($vmparams)
$newContent = $content
foreach ($agentArg in $agentArgs) {
    $pattern = '(?i)(?<!\S)' + [regex]::Escape($agentArg) + '(?=\s|$)\s*'
    $newContent = [regex]::Replace($newContent, $pattern, '')
}
if ($newContent -eq $content) {
    Write-Host 'Neither Prepatcher javaagent entry was found; nothing changed.' -ForegroundColor Yellow
    exit 0
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "$vmparams.spp-uninstall-backup-$timestamp"
Copy-Item -LiteralPath $vmparams -Destination $backup -Force
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($vmparams, $newContent, $encoding)
Write-Host 'Removed both StarsectorPrepatcher javaagents from vmparams.' -ForegroundColor Green
Write-Host "vmparams backup: $backup"
