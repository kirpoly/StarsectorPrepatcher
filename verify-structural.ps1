param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $CoreJars
)

$ErrorActionPreference = 'Stop'

$modRoot = (Resolve-Path $PSScriptRoot).Path
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$core = Join-Path $gameRoot 'starsector-core'
$build = Join-Path $modRoot '.build'
$agentClasses = Join-Path $build 'agent-classes'
$hyperClasses = Join-Path $build 'hyperspace-classes'
$testClasses = Join-Path $build 'test-classes'
$reportDir = Join-Path $build 'reports'
$utf8 = New-Object Text.UTF8Encoding($false)
$exports = @(
    '--add-exports', 'java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED',
    '--add-exports', 'java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED',
    '--add-exports', 'java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED'
)

& (Join-Path $modRoot 'build-agent.ps1')
if (Test-Path $testClasses) { Remove-Item -Recurse -Force $testClasses }
New-Item -ItemType Directory -Force -Path $testClasses, $reportDir | Out-Null

$testCp = @(
    $agentClasses,
    (Join-Path $core 'starfarer.api.jar'),
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'fs.common_obf.jar'),
    (Join-Path $core 'fs.sound_obf.jar'),
    (Join-Path $core 'lwjgl.jar'),
    (Join-Path $core 'lwjgl_util.jar')
) -join [IO.Path]::PathSeparator
$testSources = Get-ChildItem -Path (Join-Path $modRoot 'source\test') -Filter '*.java' -Recurse | ForEach-Object FullName
& javac -encoding UTF-8 -source 17 -target 17 @exports -cp $testCp -d $testClasses @testSources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }

$savedErrorActionPreference = $ErrorActionPreference
$documentationReport = Join-Path $reportDir 'documentation-consistency.txt'
$ErrorActionPreference = 'Continue'
try {
    $documentationOutput = @(& java -cp $testClasses com.starsector.prepatcher.docs.DocumentationConsistencyTest $modRoot 2>&1)
    $documentationExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$documentationLines = @($documentationOutput | ForEach-Object { $_.ToString() })
$documentationLines
[IO.File]::WriteAllLines($documentationReport, [string[]] $documentationLines, $utf8)
if ($documentationExitCode -ne 0) { throw 'Documentation consistency verification failed.' }

if ($CoreJars.Count -gt 0) {
    $selectedCoreJars = $CoreJars
} else {
    $selectedCoreJars = @(
        (Join-Path $core 'starfarer_obf.jar'),
        (Join-Path $core 'fs.common_obf.jar'),
        (Join-Path $core 'fs.sound_obf.jar')
    )
}
$classPath = @($agentClasses, $testClasses) -join [IO.Path]::PathSeparator
$structuralReport = Join-Path $reportDir 'structural-verification.txt'
$verificationConfig = Join-Path $build 'structural-all-enabled.properties'
$verificationText = [IO.File]::ReadAllText(
    (Join-Path $modRoot 'profiles\aggressive.properties'),
    [Text.Encoding]::UTF8)
foreach ($key in @('patch.loadingTextReader', 'patch.startupLogAggregation')) {
    $pattern = '(?m)^' + [regex]::Escape($key) + '[ \t]*=[ \t]*false[ \t]*(\r?)$'
    $matches = [regex]::Matches($verificationText, $pattern)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one known-disabled $key=false in aggressive profile."
    }
    $verificationText = [regex]::Replace(
        $verificationText,
        $pattern,
        { param($match) "$key=true" + $match.Groups[1].Value })
}
[IO.File]::WriteAllText(
    $verificationConfig,
    "# Generated for structural coverage only; never shipped or used by startup smoke.`n" +
        $verificationText,
    $utf8)
$ErrorActionPreference = 'Continue'
try {
    # Windows PowerShell wraps native stderr as ErrorRecord. The structural
    # harness intentionally logs agent diagnostics there, so success must be
    # decided from the native exit code rather than ErrorActionPreference=Stop.
    $structuralOutput = @(& java @exports -cp $classPath com.starsector.prepatcher.agent.StructuralCompatibilityTest $verificationConfig @selectedCoreJars 2>&1)
    $structuralExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$structuralLines = @($structuralOutput | ForEach-Object { $_.ToString() })
$structuralLines
[IO.File]::WriteAllLines($structuralReport, [string[]] $structuralLines, $utf8)
if ($structuralExitCode -ne 0) { throw 'Structural compatibility verification failed.' }

$runtimeCp = @(
    $testClasses,
    (Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar'),
    (Join-Path $core 'starfarer.api.jar'),
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'fs.common_obf.jar'),
    (Join-Path $core 'fs.sound_obf.jar'),
    (Join-Path $core 'lwjgl.jar'),
    (Join-Path $core 'lwjgl_util.jar')
) -join [IO.Path]::PathSeparator
$runtimeReport = Join-Path $reportDir 'runtime-regression.txt'
$runtimeLines = [System.Collections.Generic.List[string]]::new()
foreach ($test in @(
    'com.starsector.prepatcher.runtime.LifecycleGcRegressionTest',
    'com.starsector.prepatcher.runtime.Exp6RuntimeRegressionTest',
    'com.starsector.prepatcher.runtime.Exp8RuntimeRegressionTest',
    'com.starsector.prepatcher.runtime.LoadingSaveRuntimeRegressionTest'
)) {
    $runtimeLines.Add("== $test ==")
    $ErrorActionPreference = 'Continue'
    try {
        $out = @(& java -cp $runtimeCp $test 2>&1)
        $runtimeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $runtimeLines.AddRange([string[]] $out)
    if ($runtimeExitCode -ne 0) {
        [IO.File]::WriteAllLines($runtimeReport, $runtimeLines, $utf8)
        throw "Runtime test failed: $test"
    }
}
$runtimeLines
[IO.File]::WriteAllLines($runtimeReport, $runtimeLines, $utf8)

$hyperReport = Join-Path $reportDir 'hyperspace-verification.txt'
$ErrorActionPreference = 'Continue'
try {
    $hyperOutput = @(& java @exports -cp $hyperClasses com.starsector.prepatcher.hyperspace.OfflineVerifier (Join-Path $core 'starfarer_obf.jar') (Join-Path $core 'starfarer.api.jar') $hyperReport 2>&1)
    $hyperExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$hyperLines = @($hyperOutput | ForEach-Object { $_.ToString() })
$hyperLines
if ($hyperExitCode -ne 0) { throw 'Hyperspace offline verification failed.' }

$startupReport = Join-Path $reportDir 'startup-smoke.txt'
$mainAgentJar = Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar'
$hyperAgentJar = Join-Path $modRoot 'agent\StarsectorPrepatcherHyperspaceAgent.jar'
$ErrorActionPreference = 'Continue'
try {
    $startupOutput = @(& java "-javaagent:$mainAgentJar" "-javaagent:$hyperAgentJar" -version 2>&1)
    $startupExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$startupLines = @($startupOutput | ForEach-Object { $_.ToString() })
$startupLines
[IO.File]::WriteAllLines($startupReport, [string[]] $startupLines, $utf8)
if ($startupExitCode -ne 0) { throw 'Combined javaagent startup smoke failed.' }

Write-Host 'Documentation/structural/runtime/hyperspace/startup verification completed.' -ForegroundColor Green
