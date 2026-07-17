[CmdletBinding()]
param(
    [ValidateSet('Vanilla', 'FasterRendering', 'Both')]
    [string] $Target = 'Vanilla'
)

$ErrorActionPreference = 'Stop'

$modRoot = (Resolve-Path $PSScriptRoot).Path
$modFolder = Split-Path $modRoot -Leaf
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$agentJar = Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar'
$agentArg = "-javaagent:../mods/$modFolder/agent/StarsectorPrepatcherAgent.jar"

if (-not (Test-Path -LiteralPath $agentJar -PathType Leaf)) {
    throw "Agent JAR not found: $agentJar"
}
if ($modFolder -ne 'StarsectorPrepatcher') {
    Write-Warning "The mod folder is named '$modFolder'. The generated javaagent paths will use that name; do not rename it after installation."
}
if ($modFolder -match '\s') {
    throw "The mod folder path contains whitespace. Rename the folder to 'StarsectorPrepatcher' and run this installer again."
}

$availableTargets = @(
    [pscustomobject]@{
        Name = 'Vanilla'
        Path = Join-Path $gameRoot 'vmparams'
        RequiresJavaPrefix = $true
        IsArgumentFile = $false
    },
    [pscustomobject]@{
        Name = 'FasterRendering'
        Path = Join-Path $gameRoot 'starsector-core\fr.vmparams'
        RequiresJavaPrefix = $false
        IsArgumentFile = $true
    }
)
$selectedTargets = switch ($Target) {
    'Both' { $availableTargets }
    default { @($availableTargets | Where-Object Name -eq $Target) }
}

function New-InstallationPlan {
    param(
        [Parameter(Mandatory)] [pscustomobject] $TargetSpec,
        [Parameter(Mandatory)] [string] $Content
    )

    $javaPrefixPattern = '^\s*(?:"[^"]*javaw?\.exe"|[^\s]*javaw?\.exe)\s+'
    # Accept both common quoting forms used by Windows launch commands:
    # -javaagent:"path with spaces" and "-javaagent:path with spaces".
    # Missing the latter would let us insert before an existing javaagent while
    # still passing the final "Prepatcher is last" check.
    $javaAgentPattern = '(?i)(?<!\S)(?:"-javaagent:[^"]+"|-javaagent:(?:"[^"]*"|[^\s"]+))'
    $tokenPattern = '(?i)(?<!\S)' + [regex]::Escape($agentArg) + '(?=\s|$)[ \t]*'
    if ($TargetSpec.IsArgumentFile) {
        # Remove a managed entry that occupies its own argfile line together
        # with that line break. Otherwise every idempotent reinstall would
        # accumulate an extra blank line before -classpath.
        $managedLinePattern = '(?im)^[ \t]*' + [regex]::Escape($agentArg) + '[ \t]*(?:\r?\n|$)'
        $working = [regex]::Replace($Content, $managedLinePattern, '')
        $working = [regex]::Replace($working, $tokenPattern, '')
    } else {
        $working = [regex]::Replace($Content, $tokenPattern, '')
    }

    if ($TargetSpec.RequiresJavaPrefix) {
        $prefixMatch = [regex]::Match(
            $working,
            $javaPrefixPattern,
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        if (-not $prefixMatch.Success) {
            throw "Could not find java.exe/javaw.exe at the beginning of $($TargetSpec.Path). No changes were made."
        }
        $javaOptionsStart = $prefixMatch.Index + $prefixMatch.Length
    } else {
        # Faster Rendering launches Java with @fr.vmparams, so this file starts
        # directly with JVM options and intentionally has no java.exe prefix.
        $javaOptionsStart = 0
    }

    $classpathMatch = $null
    if ($TargetSpec.IsArgumentFile) {
        $classpathMatch = [regex]::Match(
            $working,
            '^[ \t]*(?:-classpath|-cp|--class-path)(?=[ \t=\r\n]|$)',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
                [System.Text.RegularExpressions.RegexOptions]::Multiline
        )
        if (-not $classpathMatch.Success) {
            throw "Could not find the Java classpath option in $($TargetSpec.Path). No changes were made."
        }
    }

    $otherAgents = [regex]::Matches($working.Substring($javaOptionsStart), $javaAgentPattern)
    if ($otherAgents.Count -gt 0) {
        $lastAgent = $otherAgents[$otherAgents.Count - 1]
        $insertAt = $javaOptionsStart + $lastAgent.Index + $lastAgent.Length
        if ($TargetSpec.IsArgumentFile -and $insertAt -gt $classpathMatch.Index) {
            throw "Found a javaagent after the classpath option in $($TargetSpec.Path). No changes were made."
        }
        if ($TargetSpec.IsArgumentFile) {
            $lineBreak = if ($working.Contains("`r`n")) { "`r`n" } else { "`n" }
            $newContent = $working.Substring(0, $insertAt) + $lineBreak + $agentArg + $working.Substring($insertAt)
        } else {
            $newContent = $working.Substring(0, $insertAt) + ' ' + $agentArg + $working.Substring($insertAt)
        }
    } elseif ($TargetSpec.IsArgumentFile) {
        $lineBreak = if ($working.Contains("`r`n")) { "`r`n" } else { "`n" }
        $insertAt = $classpathMatch.Index
        $before = $working.Substring(0, $insertAt)
        $after = $working.Substring($insertAt)
        $beforeSeparator = if ($before.Length -eq 0 -or $before.EndsWith("`n")) { '' } else { $lineBreak }
        $afterSeparator = if ($after.Length -eq 0 -or $after.StartsWith("`r") -or $after.StartsWith("`n")) { '' } else { $lineBreak }
        $newContent = $before + $beforeSeparator + $agentArg + $afterSeparator + $after
    } else {
        $newContent = $working.Substring(0, $javaOptionsStart) + $agentArg + ' ' + $working.Substring($javaOptionsStart)
    }

    if ($TargetSpec.RequiresJavaPrefix) {
        $finalPrefix = [regex]::Match(
            $newContent,
            $javaPrefixPattern,
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        if (-not $finalPrefix.Success) {
            throw "Could not safely install the prepatcher agent in $($TargetSpec.Path). No changes were made."
        }
        $finalOptionsStart = $finalPrefix.Index + $finalPrefix.Length
    } else {
        $finalOptionsStart = 0
    }
    $orderedAgents = [regex]::Matches($newContent.Substring($finalOptionsStart), $javaAgentPattern)
    if ($orderedAgents.Count -lt 1) {
        throw "Could not safely install the prepatcher agent in $($TargetSpec.Path). No changes were made."
    }
    $actual = $orderedAgents[$orderedAgents.Count - 1].Value
    if (-not $actual.Equals($agentArg, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Could not safely place the prepatcher agent after the existing javaagents in $($TargetSpec.Path). No changes were made."
    }

    [pscustomobject]@{
        TargetSpec = $TargetSpec
        OriginalContent = $Content
        NewContent = $newContent
        ExistingAgentCount = $otherAgents.Count
    }
}

# Preflight every selected file before writing any of them. This avoids a
# predictable half-installed -Target Both result when one target is missing or malformed.
$plans = foreach ($targetSpec in $selectedTargets) {
    if (-not (Test-Path -LiteralPath $targetSpec.Path -PathType Leaf)) {
        throw "$($targetSpec.Name) vmparams not found: $($targetSpec.Path)`nThe mod must be inside <Starsector>\mods\$modFolder."
    }
    $content = [System.IO.File]::ReadAllText($targetSpec.Path)
    if ($targetSpec.RequiresJavaPrefix -and $content -notmatch '(?i)-noverify') {
        Write-Warning "$($targetSpec.Path) does not contain -noverify. Vanilla 0.98a-RC8 normally has it; keep it enabled because the obfuscated core contains identifiers rejected by full bytecode verification."
    }
    New-InstallationPlan -TargetSpec $targetSpec -Content $content
}

$encoding = New-Object System.Text.UTF8Encoding($false)
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
$changed = 0
foreach ($plan in $plans) {
    $path = $plan.TargetSpec.Path
    if ($plan.NewContent -eq $plan.OriginalContent) {
        Write-Host "$($plan.TargetSpec.Name): the StarsectorPrepatcher javaagent is already installed in the correct order." -ForegroundColor Yellow
        continue
    }

    $backup = "$path.spp-backup-$timestamp"
    Copy-Item -LiteralPath $path -Destination $backup
    [System.IO.File]::WriteAllText($path, $plan.NewContent, $encoding)
    $changed++

    Write-Host "$($plan.TargetSpec.Name): installed the StarsectorPrepatcher javaagent." -ForegroundColor Green
    Write-Host "vmparams backup: $backup"
    Write-Host "Placed after existing javaagents: $($plan.ExistingAgentCount)"
}

if ($changed -eq 0) {
    Write-Host 'No files changed.' -ForegroundColor Yellow
}
Write-Host "Managed entry: $agentArg"
Write-Host 'No --add-exports vmparams flags are required; the agent exports its ASM modules at startup.'
