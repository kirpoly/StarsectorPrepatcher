$ErrorActionPreference = 'Stop'

$modRoot = (Resolve-Path $PSScriptRoot).Path
$modFolder = Split-Path $modRoot -Leaf
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$vmparams = Join-Path $gameRoot 'vmparams'

$agentSpecs = @(
    [pscustomobject]@{
        Jar = Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar'
        Arg = "-javaagent:../mods/$modFolder/agent/StarsectorPrepatcherAgent.jar"
    },
    [pscustomobject]@{
        Jar = Join-Path $modRoot 'agent\StarsectorPrepatcherHyperspaceAgent.jar'
        Arg = "-javaagent:../mods/$modFolder/agent/StarsectorPrepatcherHyperspaceAgent.jar"
    }
)

foreach ($spec in $agentSpecs) {
    if (-not (Test-Path -LiteralPath $spec.Jar -PathType Leaf)) {
        throw "Agent JAR not found: $($spec.Jar)"
    }
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

# Remove any existing entries for this prepatcher installation, then append the
# pair after every other -javaagent option so agent ordering stays deterministic.
$working = $content
$managedArgs = @(
    $agentSpecs[0].Arg,
    $agentSpecs[1].Arg
) | Select-Object -Unique
foreach ($agentArg in $managedArgs) {
    $tokenPattern = '(?i)(?<!\S)' + [regex]::Escape($agentArg) + '(?=\s|$)\s*'
    $working = [regex]::Replace($working, $tokenPattern, '')
}

$prefixMatch = [regex]::Match($working, $javaPrefixPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
if (-not $prefixMatch.Success) {
    throw 'Could not re-locate java.exe/javaw.exe after normalizing agent options. No changes were made.'
}
$javaOptionsStart = $prefixMatch.Index + $prefixMatch.Length
$javaAgentPattern = '(?i)(?<!\S)-javaagent:(?:"[^"]*"|[^\s"]+)'
$otherAgents = [regex]::Matches($working.Substring($javaOptionsStart), $javaAgentPattern)
$bundle = ($agentSpecs | ForEach-Object Arg) -join ' '

if ($otherAgents.Count -gt 0) {
    $lastAgent = $otherAgents[$otherAgents.Count - 1]
    $insertAt = $javaOptionsStart + $lastAgent.Index + $lastAgent.Length
    $newContent = $working.Substring(0, $insertAt) + ' ' + $bundle + $working.Substring($insertAt)
} else {
    $insertAt = $javaOptionsStart
    $newContent = $working.Substring(0, $insertAt) + $bundle + ' ' + $working.Substring($insertAt)
}

# Validate that the final two javaagent options are our main and hyperspace agents.
$finalPrefix = [regex]::Match($newContent, $javaPrefixPattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$orderedAgents = [regex]::Matches($newContent.Substring($finalPrefix.Index + $finalPrefix.Length), $javaAgentPattern)
if ($orderedAgents.Count -lt 2) {
    throw 'Could not safely install both prepatcher agents. No changes were made.'
}
$expectedMain = $agentSpecs[0].Arg
$expectedHyper = $agentSpecs[1].Arg
$actualMain = $orderedAgents[$orderedAgents.Count - 2].Value
$actualHyper = $orderedAgents[$orderedAgents.Count - 1].Value
if (-not $actualMain.Equals($expectedMain, [System.StringComparison]::OrdinalIgnoreCase) -or
    -not $actualHyper.Equals($expectedHyper, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Could not safely place the prepatcher agent pair after the existing javaagents. No changes were made.'
}

if ($newContent -eq $content) {
    Write-Host 'Both StarsectorPrepatcher javaagents are already installed in the correct order.' -ForegroundColor Yellow
    foreach ($spec in $agentSpecs) { Write-Host $spec.Arg }
    exit 0
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "$vmparams.spp-backup-$timestamp"
Copy-Item -LiteralPath $vmparams -Destination $backup -Force
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($vmparams, $newContent, $encoding)

Write-Host 'Installed the StarsectorPrepatcher agent pair.' -ForegroundColor Green
Write-Host "vmparams backup: $backup"
Write-Host "Placed after existing javaagents: $($otherAgents.Count)"
foreach ($spec in $agentSpecs) { Write-Host "Added: $($spec.Arg)" }
Write-Host 'No --add-exports vmparams flags are required; the hyperspace agent exports its ASM modules at startup.'
