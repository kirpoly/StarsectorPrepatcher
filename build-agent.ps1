$ErrorActionPreference = 'Stop'
$modRoot = (Resolve-Path $PSScriptRoot).Path
$gameRoot = (Resolve-Path (Join-Path $modRoot '..\..')).Path
$core = Join-Path $gameRoot 'starsector-core'
$build = Join-Path $modRoot '.build'
$agentClasses = Join-Path $build 'agent-classes'
$hyperClasses = Join-Path $build 'hyperspace-classes'
$bootstrapClasses = Join-Path $build 'bootstrap-classes'
Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $agentClasses, $hyperClasses, $bootstrapClasses, (Join-Path $modRoot 'agent'), (Join-Path $modRoot 'jars') | Out-Null
$agentSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $modRoot 'source\agent') | ForEach-Object FullName
$hyperSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $modRoot 'source\hyperspace') | ForEach-Object FullName
$bootstrapSources = Get-ChildItem -Recurse -Filter *.java (Join-Path $modRoot 'source\bootstrap') | ForEach-Object FullName
$agentCp = @((Join-Path $core 'starfarer.api.jar'),(Join-Path $core 'starfarer_obf.jar'),(Join-Path $core 'fs.common_obf.jar'),(Join-Path $core 'fs.sound_obf.jar'),(Join-Path $core 'lwjgl.jar'),(Join-Path $core 'lwjgl_util.jar')) -join ';'
$bootstrapCp = @((Join-Path $core 'starfarer.api.jar'),(Join-Path $core 'starfarer_obf.jar'),(Join-Path $core 'log4j-1.2.9.jar')) -join ';'
$exports = @('--add-exports','java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED','--add-exports','java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED','--add-exports','java.base/jdk.internal.org.objectweb.asm.tree.analysis=ALL-UNNAMED')
& javac -encoding UTF-8 -source 17 -target 17 @exports -cp $agentCp -d $agentClasses $agentSources
if ($LASTEXITCODE -ne 0) { throw 'Main agent compilation failed.' }
& javac -encoding UTF-8 -source 17 -target 17 @exports -cp $agentCp -d $hyperClasses $hyperSources
if ($LASTEXITCODE -ne 0) { throw 'Hyperspace agent compilation failed.' }
Copy-Item (Join-Path $modRoot 'source\hyperspace-resources\hyperspace-targets.properties') $hyperClasses -Force
& javac -encoding UTF-8 -source 17 -target 17 -cp $bootstrapCp -d $bootstrapClasses $bootstrapSources
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap compilation failed.' }
$utf8 = New-Object Text.UTF8Encoding($false)
$agentManifest = Join-Path $build 'agent.mf'
$hyperManifest = Join-Path $build 'hyperspace-agent.mf'
$bootstrapManifest = Join-Path $build 'bootstrap.mf'
[IO.File]::WriteAllText($agentManifest, "Manifest-Version: 1.0`nImplementation-Title: StarsectorPrepatcher Agent`nImplementation-Version: 0.8.0`nPremain-Class: com.starsector.prepatcher.agent.PrepatcherAgent`nCan-Redefine-Classes: false`nCan-Retransform-Classes: false`n`n", $utf8)
[IO.File]::WriteAllText($hyperManifest, "Manifest-Version: 1.0`nImplementation-Title: StarsectorPrepatcher Hyperspace Agent`nImplementation-Version: 0.8.0`nPremain-Class: com.starsector.prepatcher.hyperspace.HyperspaceAgent`nCan-Redefine-Classes: false`nCan-Retransform-Classes: false`n`n", $utf8)
[IO.File]::WriteAllText($bootstrapManifest, "Manifest-Version: 1.0`nImplementation-Title: StarsectorPrepatcher Bootstrap`nImplementation-Version: 0.8.0`n`n", $utf8)
& jar cfm (Join-Path $modRoot 'agent\StarsectorPrepatcherAgent.jar') $agentManifest -C $agentClasses .
if ($LASTEXITCODE -ne 0) { throw 'Main agent JAR creation failed.' }
& jar cfm (Join-Path $modRoot 'agent\StarsectorPrepatcherHyperspaceAgent.jar') $hyperManifest -C $hyperClasses .
if ($LASTEXITCODE -ne 0) { throw 'Hyperspace agent JAR creation failed.' }
& jar cfm (Join-Path $modRoot 'jars\StarsectorPrepatcherBootstrap.jar') $bootstrapManifest -C $bootstrapClasses .
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap JAR creation failed.' }

# Keep the release manifest synchronized with the exact tree produced by this build.
# Runtime logs, build intermediates and SHA256SUMS.txt itself are intentionally excluded.
$checksumFiles = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
Get-ChildItem -LiteralPath $modRoot -File -Force | Where-Object {
    $_.Name -ne 'SHA256SUMS.txt'
} | ForEach-Object { $checksumFiles.Add($_) }
foreach ($directory in @('agent', 'docs', 'jars', 'profiles', 'source')) {
    Get-ChildItem -LiteralPath (Join-Path $modRoot $directory) -File -Force -Recurse |
        ForEach-Object { $checksumFiles.Add($_) }
}
$logsReadme = Join-Path $modRoot 'logs\README.txt'
if (-not (Test-Path -LiteralPath $logsReadme -PathType Leaf)) {
    throw 'Required checksum input logs\README.txt is missing.'
}
$checksumFiles.Add((Get-Item -LiteralPath $logsReadme -Force))

[string[]] $checksumRelativePaths = $checksumFiles | ForEach-Object {
    $_.FullName.Substring($modRoot.Length + 1).Replace('\', '/')
}
[Array]::Sort($checksumRelativePaths, [StringComparer]::Ordinal)
$checksumLines = foreach ($relativePath in $checksumRelativePaths) {
    $nativePath = $relativePath.Replace('/', [IO.Path]::DirectorySeparatorChar)
    $digest = (Get-FileHash -LiteralPath (Join-Path $modRoot $nativePath) -Algorithm SHA256).Hash.ToLowerInvariant()
    "$digest  $relativePath"
}
$checksumTarget = Join-Path $modRoot 'SHA256SUMS.txt'
$checksumTemp = Join-Path $build 'SHA256SUMS.txt.tmp'
$checksumBackup = Join-Path $build 'SHA256SUMS.txt.bak'
[IO.File]::WriteAllText($checksumTemp, (($checksumLines -join "`n") + "`n"), $utf8)
if (Test-Path -LiteralPath $checksumTarget -PathType Leaf) {
    [IO.File]::Replace($checksumTemp, $checksumTarget, $checksumBackup)
    Remove-Item -LiteralPath $checksumBackup -Force
} else {
    [IO.File]::Move($checksumTemp, $checksumTarget)
}

Write-Host 'Build and checksum manifest completed.' -ForegroundColor Green
