[CmdletBinding()]
param(
    [ValidateSet('Vanilla', 'FasterRendering', 'Both')]
    [string] $Target = 'Vanilla'
)

$ErrorActionPreference = 'Stop'

$modRoot = (Resolve-Path $PSScriptRoot).Path
$modFolder = Split-Path $modRoot -Leaf
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$agentArg = "-javaagent:../mods/$modFolder/agent/StarsectorPrepatcherAgent.jar"
$availableTargets = @(
    [pscustomobject]@{
        Name = 'Vanilla'
        Path = Join-Path $gameRoot 'vmparams'
        IsArgumentFile = $false
    },
    [pscustomobject]@{
        Name = 'FasterRendering'
        Path = Join-Path $gameRoot 'starsector-core\fr.vmparams'
        IsArgumentFile = $true
    }
)
$selectedTargets = switch ($Target) {
    'Both' { $availableTargets }
    default { @($availableTargets | Where-Object Name -eq $Target) }
}

# Preflight every selected file before removing an entry from any of them.
$plans = foreach ($targetSpec in $selectedTargets) {
    if (-not (Test-Path -LiteralPath $targetSpec.Path -PathType Leaf)) {
        throw "$($targetSpec.Name) vmparams not found: $($targetSpec.Path)"
    }
    $content = [System.IO.File]::ReadAllText($targetSpec.Path)
    $pattern = '(?i)(?<!\S)' + [regex]::Escape($agentArg) + '(?=\s|$)[ \t]*'
    if ($targetSpec.IsArgumentFile) {
        $managedLinePattern = '(?im)^[ \t]*' + [regex]::Escape($agentArg) + '[ \t]*(?:\r?\n|$)'
        $newContent = [regex]::Replace($content, $managedLinePattern, '')
        $newContent = [regex]::Replace($newContent, $pattern, '')
    } else {
        $newContent = [regex]::Replace($content, $pattern, '')
    }
    [pscustomobject]@{
        TargetSpec = $targetSpec
        OriginalContent = $content
        NewContent = $newContent
    }
}

$encoding = New-Object System.Text.UTF8Encoding($false)
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
$changed = 0
foreach ($plan in $plans) {
    $path = $plan.TargetSpec.Path
    if ($plan.NewContent -eq $plan.OriginalContent) {
        Write-Host "$($plan.TargetSpec.Name): the Prepatcher javaagent entry was not found; nothing changed." -ForegroundColor Yellow
        continue
    }

    $backup = "$path.spp-uninstall-backup-$timestamp"
    Copy-Item -LiteralPath $path -Destination $backup
    [System.IO.File]::WriteAllText($path, $plan.NewContent, $encoding)
    $changed++

    Write-Host "$($plan.TargetSpec.Name): removed the StarsectorPrepatcher javaagent." -ForegroundColor Green
    Write-Host "vmparams backup: $backup"
}

if ($changed -eq 0) {
    Write-Host 'No files changed.' -ForegroundColor Yellow
}
