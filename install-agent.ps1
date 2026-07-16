$ErrorActionPreference = 'Stop'

$modRoot = (Resolve-Path $PSScriptRoot).Path
$modFolder = Split-Path $modRoot -Leaf
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$vmparams = Join-Path $gameRoot 'vmparams'
$agentJar = Join-Path $modRoot 'agent\StarsectorMapOptimizerAgent.jar'
$agentArg = "-javaagent:../mods/$modFolder/agent/StarsectorMapOptimizerAgent.jar"
$javaAgentPattern = '(?i)(?<!\S)-javaagent:(?:"[^"]*"|[^\s"])+'
$optimizerAgentPattern = '(?i)(?<!\S)' + [regex]::Escape($agentArg) + '(?=\s|$)'

if (-not (Test-Path -LiteralPath $agentJar -PathType Leaf)) {
    throw "Agent JAR not found: $agentJar"
}
if (-not (Test-Path -LiteralPath $vmparams -PathType Leaf)) {
    throw "vmparams not found: $vmparams`nThe mod must be inside <Starsector>\mods\$modFolder."
}
if ($modFolder -ne 'StarsectorMapOptimizer') {
    Write-Warning "The mod folder is named '$modFolder'. The generated javaagent path will use that name; do not rename it after installation."
}
if ($modFolder -match '\s') {
    throw "The mod folder path contains whitespace. Rename the folder to 'StarsectorMapOptimizer' and run this installer again."
}

$content = [System.IO.File]::ReadAllText($vmparams)
$match = [regex]::Match($content, '^\s*(?:"[^"]*javaw?\.exe"|[^\s]*javaw?\.exe)\s+', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
if (-not $match.Success) {
    throw 'Could not find java.exe/javaw.exe at the beginning of vmparams. No changes were made.'
}
if ($content -notmatch '(?i)-noverify') {
    Write-Warning 'vmparams does not contain -noverify. Vanilla 0.98a-RC8 normally has it; keep it enabled because the obfuscated core contains identifiers rejected by full bytecode verification.'
}

$javaOptionsStart = $match.Index + $match.Length
$optimizerAgents = [regex]::Matches($content.Substring($javaOptionsStart), $optimizerAgentPattern)
if ($optimizerAgents.Count -gt 1) {
    throw 'Multiple Map Optimizer javaagent entries were found. Remove the duplicates manually; no changes were made.'
}

$workingContent = $content
$reordered = $false
if ($optimizerAgents.Count -eq 1) {
    $allAgents = [regex]::Matches($content.Substring($javaOptionsStart), $javaAgentPattern)
    $lastAgent = $allAgents[$allAgents.Count - 1]
    $optimizerAgent = $optimizerAgents[0]
    if ($optimizerAgent.Index -eq $lastAgent.Index -and
            $optimizerAgent.Length -eq $lastAgent.Length) {
        Write-Host 'The Map Optimizer javaagent is already present after the other javaagents.' -ForegroundColor Yellow
        Write-Host $agentArg
        exit 0
    }

    $removeAt = $javaOptionsStart + $optimizerAgent.Index
    $removeLength = $optimizerAgent.Length
    while ($removeAt + $removeLength -lt $content.Length -and
            [char]::IsWhiteSpace($content[$removeAt + $removeLength])) {
        $removeLength++
    }
    $workingContent = $content.Remove($removeAt, $removeLength)
    $reordered = $true

    $match = [regex]::Match($workingContent, '^\s*(?:"[^"]*javaw?\.exe"|[^\s]*javaw?\.exe)\s+', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $javaOptionsStart = $match.Index + $match.Length
}

$existingAgents = [regex]::Matches($workingContent.Substring($javaOptionsStart), $javaAgentPattern)
if ($existingAgents.Count -gt 0) {
    $lastAgent = $existingAgents[$existingAgents.Count - 1]
    $insertAt = $javaOptionsStart + $lastAgent.Index + $lastAgent.Length
    $newContent = $workingContent.Substring(0, $insertAt) + ' ' + $agentArg + $workingContent.Substring($insertAt)
} else {
    $insertAt = $javaOptionsStart
    $newContent = $workingContent.Substring(0, $insertAt) + $agentArg + ' ' + $workingContent.Substring($insertAt)
}

$orderedAgents = [regex]::Matches($newContent.Substring($javaOptionsStart), $javaAgentPattern)
if ($orderedAgents.Count -eq 0 -or
        -not $orderedAgents[$orderedAgents.Count - 1].Value.Equals(
            $agentArg, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Could not safely place Map Optimizer after the existing javaagents. No changes were made.'
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "$vmparams.smo-backup-$timestamp"
Copy-Item -LiteralPath $vmparams -Destination $backup -Force
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($vmparams, $newContent, $encoding)

if ($reordered) {
    Write-Host 'Moved Starsector Map Optimizer after the other javaagents.' -ForegroundColor Green
} else {
    Write-Host 'Installed Starsector Map Optimizer javaagent.' -ForegroundColor Green
}
Write-Host "vmparams backup: $backup"
Write-Host "Added: $agentArg"
Write-Host "Placed after existing javaagents: $($existingAgents.Count)"
Write-Host 'The telemetry javaagent may remain in the same vmparams; multiple -javaagent options are supported.'
