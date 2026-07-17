$ErrorActionPreference = 'Stop'
$modRoot = (Resolve-Path $PSScriptRoot).Path
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$core = Join-Path $gameRoot 'starsector-core'
$build = Join-Path $modRoot '.build'
$agentClasses = Join-Path $build 'agent-classes'
$bootstrapClasses = Join-Path $build 'bootstrap-classes'

Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $agentClasses, $bootstrapClasses | Out-Null

$agentSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $modRoot 'source\agent') | ForEach-Object FullName
$bootstrapSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $modRoot 'source\bootstrap') | ForEach-Object FullName
$agentCp = @(
    (Join-Path $core 'starfarer.api.jar'),
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'lwjgl.jar'),
    (Join-Path $core 'lwjgl_util.jar')
) -join ';'
$bootstrapCp = @(
    (Join-Path $core 'starfarer.api.jar'),
    (Join-Path $core 'starfarer_obf.jar'),
    (Join-Path $core 'log4j-1.2.9.jar')
) -join ';'

& javac -source 17 -target 17 `
    --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED `
    --add-exports java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED `
    --add-exports java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED `
    -cp $agentCp -d $agentClasses $agentSources
if ($LASTEXITCODE -ne 0) { throw 'Agent compilation failed.' }

& javac -source 17 -target 17 -cp $bootstrapCp -d $bootstrapClasses $bootstrapSources
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap compilation failed.' }

$agentManifest = Join-Path $build 'agent.mf'
$bootstrapManifest = Join-Path $build 'bootstrap.mf'
[IO.File]::WriteAllText($agentManifest, "Manifest-Version: 1.0`nPremain-Class: com.starsector.mapoptimizer.agent.MapOptimizerAgent`nCan-Redefine-Classes: false`nCan-Retransform-Classes: false`n`n", (New-Object Text.UTF8Encoding($false)))
[IO.File]::WriteAllText($bootstrapManifest, "Manifest-Version: 1.0`nImplementation-Title: Starsector Map Optimizer Bootstrap`nImplementation-Version: 0.4.0-exp6`n`n", (New-Object Text.UTF8Encoding($false)))

& jar cfm (Join-Path $modRoot 'agent\StarsectorMapOptimizerAgent.jar') $agentManifest -C $agentClasses .
if ($LASTEXITCODE -ne 0) { throw 'Agent JAR creation failed.' }
& jar cfm (Join-Path $modRoot 'jars\StarsectorMapOptimizerBootstrap.jar') $bootstrapManifest -C $bootstrapClasses .
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap JAR creation failed.' }
Write-Host 'Build completed.' -ForegroundColor Green
