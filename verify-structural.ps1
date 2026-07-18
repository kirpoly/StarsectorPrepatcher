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
$testClasses = Join-Path $build 'test-classes'
$frSmokeClasses = Join-Path $build 'fr-smoke-classes'
$reportDir = Join-Path $build 'reports'
$utf8 = New-Object Text.UTF8Encoding($false)
$exports = @(
    '--add-exports', 'java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED',
    '--add-exports', 'java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED',
    '--add-exports', 'java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED'
)

& (Join-Path $modRoot 'build-agent.ps1')
if (Test-Path $testClasses) { Remove-Item -Recurse -Force $testClasses }
New-Item -ItemType Directory -Force -Path $testClasses, $frSmokeClasses, $reportDir | Out-Null

$testCp = @(
    $agentClasses,
    (Join-Path $core 'starfarer.api.jar'),
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'fs.common_obf.jar'),
    (Join-Path $core 'fs.sound_obf.jar'),
    (Join-Path $core 'lwjgl.jar'),
    (Join-Path $core 'lwjgl_util.jar')
) -join [IO.Path]::PathSeparator
$frSmokeSource = Join-Path $modRoot `
    'source\test\com\starsector\prepatcher\fr\FasterRenderingLoaderSmokeTest.java'
$testSources = Get-ChildItem -Path (Join-Path $modRoot 'source\test') -Filter '*.java' -Recurse |
    Where-Object { $_.FullName -ne $frSmokeSource } |
    ForEach-Object FullName
& javac -encoding UTF-8 -source 17 -target 17 @exports -cp $testCp -d $testClasses @testSources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& javac -encoding UTF-8 -source 17 -target 17 -d $frSmokeClasses $frSmokeSource
if ($LASTEXITCODE -ne 0) { throw 'FR smoke harness compilation failed.' }

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
        (Join-Path $core 'fs.sound_obf.jar'),
        (Join-Path $core 'starfarer.api.jar')
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

$directMarketTransformerReport = Join-Path $reportDir 'direct-market-transformer.txt'
$ErrorActionPreference = 'Continue'
try {
    $directMarketTransformerCp = @($testClasses, $testCp) -join [IO.Path]::PathSeparator
    $directMarketTransformerOutput = @(& java @exports -cp $directMarketTransformerCp `
        com.starsector.prepatcher.agent.DirectMarketObserveTransformerTest 2>&1)
    $directMarketTransformerExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$directMarketTransformerLines = @(
    $directMarketTransformerOutput | ForEach-Object { $_.ToString() })
$directMarketTransformerLines
[IO.File]::WriteAllLines(
    $directMarketTransformerReport, [string[]] $directMarketTransformerLines, $utf8)
if ($directMarketTransformerExitCode -ne 0) {
    throw 'Direct Market.advance transformer verification failed.'
}

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
    'com.starsector.prepatcher.runtime.RemoteMarketSchedulerRuntimeTest',
    'com.starsector.prepatcher.runtime.DirectMarketObservationRuntimeTest',
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
    $hyperCp = @($testClasses, $testCp) -join [IO.Path]::PathSeparator
    $hyperOutput = @(& java @exports -cp $hyperCp com.starsector.prepatcher.agent.HyperspaceCompatibilityTest $verificationConfig (Join-Path $core 'starfarer_obf.jar') (Join-Path $core 'starfarer.api.jar') $hyperReport 2>&1)
    $hyperExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$hyperLines = @($hyperOutput | ForEach-Object { $_.ToString() })
$hyperLines
if ($hyperExitCode -ne 0) { throw 'Hyperspace offline verification failed.' }

$startupReport = Join-Path $reportDir 'startup-smoke.txt'
$mainAgentJar = Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar'
$ErrorActionPreference = 'Continue'
try {
    $startupOutput = @(& java "-javaagent:$mainAgentJar" -version 2>&1)
    $startupExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$startupLines = @($startupOutput | ForEach-Object { $_.ToString() })
$startupLines
[IO.File]::WriteAllLines($startupReport, [string[]] $startupLines, $utf8)
if ($startupExitCode -ne 0) { throw 'Javaagent startup smoke failed.' }

$frSmokeReport = Join-Path $reportDir 'faster-rendering-loader-smoke.txt'
$frJar = Join-Path $core 'fr.jar'
if (-not (Test-Path -LiteralPath $frJar -PathType Leaf)) {
    $frSmokeLines = @("SKIPPED Faster Rendering loader smoke: fr.jar not found at $frJar")
    $frSmokeLines
    [IO.File]::WriteAllLines($frSmokeReport, [string[]] $frSmokeLines, $utf8)
} else {
    # Keep agent classes out of this classpath. Faster Rendering must place the
    # javaagent in JavaAgentLoader while defining the injected runtime hooks in
    # its custom system loader; adding agent-classes here would hide that split.
    $frSmokeCp = @(
        $frJar,
        $frSmokeClasses,
        (Join-Path $core 'janino.jar'),
        (Join-Path $core 'commons-compiler.jar'),
        (Join-Path $core 'commons-compiler-jdk.jar'),
        (Join-Path $core 'starfarer.api.jar'),
        (Join-Path $core 'starfarer_obf.jar'),
        (Join-Path $core 'jogg-0.0.7.jar'),
        (Join-Path $core 'jorbis-0.0.15.jar'),
        (Join-Path $core 'json.jar'),
        (Join-Path $core 'lwjgl.jar'),
        (Join-Path $core 'jinput.jar'),
        (Join-Path $core 'log4j-1.2.9.jar'),
        (Join-Path $core 'lwjgl_util.jar'),
        (Join-Path $core 'fs.sound_obf.jar'),
        (Join-Path $core 'fs.common_obf.jar'),
        (Join-Path $core 'xstream-1.4.10.jar'),
        (Join-Path $core 'txw2-3.0.2.jar'),
        (Join-Path $core 'jaxb-api-2.4.0-b180830.0359.jar'),
        (Join-Path $core 'webp-imageio-0.1.6.jar')
    ) -join [IO.Path]::PathSeparator
    $ErrorActionPreference = 'Continue'
    try {
        $frSmokeOutput = @(& java `
            '-Djava.system.class.loader=com.genir.renderer.loaders.AppClassLoader' `
            "-javaagent:$mainAgentJar=config=$verificationConfig" `
            -cp $frSmokeCp `
            com.starsector.prepatcher.fr.FasterRenderingLoaderSmokeTest `
            $mainAgentJar 2>&1)
        $frSmokeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $frSmokeLines = @($frSmokeOutput | ForEach-Object { $_.ToString() })
    $frSmokeLines
    [IO.File]::WriteAllLines($frSmokeReport, [string[]] $frSmokeLines, $utf8)
    if ($frSmokeExitCode -ne 0) { throw 'Faster Rendering loader smoke failed.' }
}

Write-Host 'Documentation/structural/runtime/hyperspace/startup/FR verification completed.' -ForegroundColor Green
