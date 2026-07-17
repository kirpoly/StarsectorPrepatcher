$ErrorActionPreference = 'Stop'

$modRoot = (Resolve-Path $PSScriptRoot).Path
$modFolder = Split-Path $modRoot -Leaf
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$vmparams = Join-Path $gameRoot 'vmparams'

$agentJar = Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar'
$agentArg = "-javaagent:../mods/$modFolder/agent/StarsectorPrepatcherAgent.jar"
if (-not (Test-Path -LiteralPath $agentJar -PathType Leaf)) {
    throw "Agent JAR not found: $agentJar"
}
if (-not (Test-Path -LiteralPath $vmparams -PathType Leaf)) {
    throw "vmparams not found: $vmparams`nThe mod must be inside <Starsector>\mods\$modFolder."
}
if ($modFolder -ne 'StarsectorPrepatcher') {
    Write-Warning "The mod folder is named '$modFolder'. The generated javaagent paths will use that name; do not rename it after installation."
}
if ($modFolder -match '\s') {
    throw "The mod folder path contains whitespace. Rename the folder to 'StarsectorPrepatcher' and run this installer again."
}

$content = [System.IO.File]::ReadAllText($vmparams)
$javaPrefixPattern = '^\s*(?:"[^"]*javaw?\.exe"|[^\s]*javaw?\.exe)\s+'
$prefixMatch = [regex]::Match($content, $javaPrefixPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
if (-not $prefixMatch.Success) {
    throw 'Could not find java.exe/javaw.exe at the beginning of vmparams. No changes were made.'
}
if ($content -notmatch '(?i)-noverify') {
    Write-Warning 'vmparams does not contain -noverify. Vanilla 0.98a-RC8 normally has it; keep it enabled because the obfuscated core contains identifiers rejected by full bytecode verification.'
}

# Remove an existing entry for this installation, then append it after every
# other -javaagent option so agent ordering stays deterministic.
$working = $content
$tokenPattern = '(?i)(?<!\S)' + [regex]::Escape($agentArg) + '(?=\s|$)\s*'
$working = [regex]::Replace($working, $tokenPattern, '')

$prefixMatch = [regex]::Match($working, $javaPrefixPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
if (-not $prefixMatch.Success) {
    throw 'Could not re-locate java.exe/javaw.exe after normalizing agent options. No changes were made.'
}
$javaOptionsStart = $prefixMatch.Index + $prefixMatch.Length
$javaAgentPattern = '(?i)(?<!\S)-javaagent:(?:"[^"]*"|[^\s"]+)'
$otherAgents = [regex]::Matches($working.Substring($javaOptionsStart), $javaAgentPattern)
if ($otherAgents.Count -gt 0) {
    $lastAgent = $otherAgents[$otherAgents.Count - 1]
    $insertAt = $javaOptionsStart + $lastAgent.Index + $lastAgent.Length
    $newContent = $working.Substring(0, $insertAt) + ' ' + $agentArg + $working.Substring($insertAt)
} else {
    $insertAt = $javaOptionsStart
    $newContent = $working.Substring(0, $insertAt) + $agentArg + ' ' + $working.Substring($insertAt)
}

# Validate that our agent is the final javaagent option.
$finalPrefix = [regex]::Match($newContent, $javaPrefixPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$orderedAgents = [regex]::Matches($newContent.Substring($finalPrefix.Index + $finalPrefix.Length), $javaAgentPattern)
if ($orderedAgents.Count -lt 1) {
    throw 'Could not safely install the prepatcher agent. No changes were made.'
}
$actual = $orderedAgents[$orderedAgents.Count - 1].Value
if (-not $actual.Equals($agentArg, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Could not safely place the prepatcher agent after the existing javaagents. No changes were made.'
}

if ($newContent -eq $content) {
    Write-Host 'The StarsectorPrepatcher javaagent is already installed in the correct order.' -ForegroundColor Yellow
    Write-Host $agentArg
    exit 0
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "$vmparams.spp-backup-$timestamp"
Copy-Item -LiteralPath $vmparams -Destination $backup -Force
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($vmparams, $newContent, $encoding)

Write-Host 'Installed the StarsectorPrepatcher javaagent.' -ForegroundColor Green
Write-Host "vmparams backup: $backup"
Write-Host "Placed after existing javaagents: $($otherAgents.Count)"
Write-Host "Added: $agentArg"
Write-Host 'No --add-exports vmparams flags are required; the agent exports its ASM modules at startup.'
