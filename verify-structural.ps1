param(
    [Parameter(Position = 0)]
    [string[]] $CoreJars
)

$ErrorActionPreference = 'Stop'
$modRoot = (Resolve-Path $PSScriptRoot).Path
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$core = Join-Path $gameRoot 'starsector-core'
$build = Join-Path $modRoot '.build'
$agentClasses = Join-Path $build 'agent-classes'
$testClasses = Join-Path $build 'test-classes'

& (Join-Path $modRoot 'build-agent.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Agent build failed.' }

New-Item -ItemType Directory -Force $testClasses | Out-Null
$testSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $modRoot 'source\test') |
    ForEach-Object FullName
& javac -source 17 -target 17 `
    --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED `
    --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED `
    --add-exports java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED `
    -cp $agentClasses -d $testClasses $testSources
if ($LASTEXITCODE -ne 0) { throw 'Structural test compilation failed.' }

if (-not $CoreJars -or $CoreJars.Count -eq 0) {
    $CoreJars = @((Join-Path $gameRoot 'starsector-core\starfarer_obf.jar'))
}
$resolvedJars = @(foreach ($jar in $CoreJars) { (Resolve-Path $jar).Path })
$classPath = "$agentClasses;$testClasses"
& java `
    --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED `
    --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED `
    --add-exports java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED `
    -cp $classPath com.starsector.mapoptimizer.agent.StructuralCompatibilityTest `
    (Join-Path $modRoot 'optimizer.properties') @resolvedJars
if ($LASTEXITCODE -ne 0) { throw 'Structural compatibility tests failed.' }

$lifecycleClassPath = @(
    $testClasses,
    (Join-Path $modRoot 'agent\StarsectorMapOptimizerAgent.jar'),
    (Join-Path $core 'starfarer.api.jar'),
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'lwjgl.jar'),
    (Join-Path $core 'lwjgl_util.jar')
) -join ';'
& java -cp $lifecycleClassPath com.starsector.mapoptimizer.runtime.LifecycleGcRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Campaign lifecycle GC regression tests failed.' }

& java -cp $lifecycleClassPath com.starsector.mapoptimizer.runtime.Exp6RuntimeRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Exp6 runtime regression tests failed.' }
