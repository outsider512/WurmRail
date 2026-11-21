[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$ServerDir = (Join-Path $PSScriptRoot 'Wurm Unlimited/WurmServerLauncher'),
    [string]$ModLoaderDir = (Join-Path $PSScriptRoot 'tools/modloader'),
    [string]$ClientDir = (Join-Path $PSScriptRoot 'Wurm Unlimited/WurmLauncher'),
    [string]$ClientModLoaderDir = (Join-Path $PSScriptRoot 'tools/clientloader'),
    [string]$Python = 'python',
    [string]$Version = '2.31.4',
    [switch]$UseExistingAssets
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)

function Resolve-JavaToolchain {
    $candidateHomes = @()
    if ($JavaHome) { $candidateHomes += $JavaHome }
    elseif ($env:JAVA_HOME) { $candidateHomes += $env:JAVA_HOME }
    if (-not $JavaHome) {
        $javacCommand = Get-Command javac -ErrorAction SilentlyContinue
        if ($javacCommand) { $candidateHomes += Split-Path (Split-Path $javacCommand.Source -Parent) -Parent }
        $candidateHomes += 'C:/Program Files/Android/Android Studio/jbr'
    }
    foreach ($candidateHome in $candidateHomes) {
        $suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
        $compiler = Join-Path $candidateHome "bin/javac$suffix"
        $runtime = Join-Path $candidateHome "bin/java$suffix"
        $jarTool = Join-Path $candidateHome "bin/jar$suffix"
        if ((Test-Path -LiteralPath $compiler -PathType Leaf) -and
            (Test-Path -LiteralPath $runtime -PathType Leaf) -and
            (Test-Path -LiteralPath $jarTool -PathType Leaf)) {
            return @{ Compiler = $compiler; Runtime = $runtime; Jar = $jarTool }
        }
    }
    throw 'A full JDK 8 or later is required. Set JAVA_HOME or pass -JavaHome to build.ps1.'
}

function Reset-BuildDirectory([string]$Path) {
    $absolutePath = [IO.Path]::GetFullPath($Path)
    $buildRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))
    if (-not $absolutePath.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clear a path outside this project's build directory: $absolutePath"
    }
    if (Test-Path -LiteralPath $absolutePath) { Remove-Item -LiteralPath $absolutePath -Recurse -Force }
    New-Item -ItemType Directory -Path $absolutePath -Force | Out-Null
}

function Invoke-JavaTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Java tool failed with exit code $LASTEXITCODE`: $Executable" }
}

if ($Version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Version must be a semantic version such as 1.0.0.' }
Write-Host 'Checking byte-preserving installer speed-default migration.'
& (Join-Path $projectRoot 'tools/InstallerConfigSmoke.ps1')
$toolchain = Resolve-JavaToolchain
$serverPath = (Resolve-Path -LiteralPath $ServerDir).Path
$loaderPath = (Resolve-Path -LiteralPath $ModLoaderDir).Path
$clientPath = (Resolve-Path -LiteralPath $ClientDir).Path
$clientLoaderPath = (Resolve-Path -LiteralPath $ClientModLoaderDir).Path
foreach ($dependency in @('modlauncher.jar','javassist.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $clientLoaderPath $dependency) -PathType Leaf)) {
        throw "Client build dependency missing: $dependency. Extract official client-modlauncher-0.15.zip into tools/clientloader or pass -ClientModLoaderDir."
    }
}
$dependencies = @(
    (Join-Path $serverPath 'server.jar'),
    (Join-Path $serverPath 'common.jar'),
    (Join-Path $loaderPath 'modlauncher.jar'),
    (Join-Path $loaderPath 'javassist.jar')
)
foreach ($dependency in $dependencies) {
    if (-not (Test-Path -LiteralPath $dependency -PathType Leaf)) { throw "Required dependency missing: $dependency" }
}
$serverLib = Join-Path $serverPath 'lib'
if (Test-Path -LiteralPath $serverLib) {
    $dependencies += @(Get-ChildItem -LiteralPath $serverLib -Filter '*.jar' -File | ForEach-Object { $_.FullName })
}
$classpath = $dependencies -join [IO.Path]::PathSeparator
$classesDir = Join-Path $projectRoot 'build/classes'
$testClassesDir = Join-Path $projectRoot 'build/test-classes'
$clientClassesDir = Join-Path $projectRoot 'build/client-classes'
$stagingDir = Join-Path $projectRoot 'build/package'
Reset-BuildDirectory $classesDir
Reset-BuildDirectory $testClassesDir
Reset-BuildDirectory $clientClassesDir
Reset-BuildDirectory $stagingDir

$sharedSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/shared/java') -Recurse -Filter '*.java' -File | Sort-Object FullName | ForEach-Object FullName)
$mainSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/main/java') -Recurse -Filter '*.java' -File | Sort-Object FullName | ForEach-Object { $_.FullName }) + $sharedSources
$testSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/test/java') -Recurse -Filter '*.java' -File | Sort-Object FullName | ForEach-Object { $_.FullName })
if ($mainSources.Count -eq 0) { throw 'No Java sources were found.' }
if ($testSources.Count -eq 0) { throw 'No tests were found; refusing to produce an untested package.' }

# javac argument files avoid Windows' command-line length limit. Forward slashes
# avoid javac treating Windows backslashes as escape sequences in quoted paths.
$mainArgsFile = Join-Path $projectRoot 'build/main-sources.txt'
$testArgsFile = Join-Path $projectRoot 'build/test-sources.txt'
$utf8NoBom = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllLines($mainArgsFile, @($mainSources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), $utf8NoBom)
[IO.File]::WriteAllLines($testArgsFile, @($testSources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), $utf8NoBom)
$versionText = (& $toolchain.Compiler -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "Unable to run javac: $versionText" }
$targetArgs = if ($versionText -match 'javac 1\.8\.') { @('-source', '1.8', '-target', '1.8') } else { @('--release', '8') }
Write-Host "Compiling with $versionText (Java 8 target)."
Invoke-JavaTool $toolchain.Compiler ($targetArgs + @('-encoding', 'UTF-8', '-Xlint:deprecation', '-cp', $classpath, '-d', $classesDir, "@$mainArgsFile"))
$testClasspath = $classesDir + [IO.Path]::PathSeparator + $classpath
Invoke-JavaTool $toolchain.Compiler ($targetArgs + @('-encoding', 'UTF-8', '-cp', $testClasspath, '-d', $testClassesDir, "@$testArgsFile"))
Write-Host 'Running dependency-free rail-network tests.'
Invoke-JavaTool $toolchain.Runtime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.TrestleAlignmentSelfTest')
Invoke-JavaTool $toolchain.Runtime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.RailNetworkSelfTest')
$compatRuntime = Join-Path $serverPath '../runtime/bin/java.exe'
if (-not (Test-Path -LiteralPath $compatRuntime -PathType Leaf)) { $compatRuntime = $toolchain.Runtime }
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.SignallingSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.TrafficRouterSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.TrafficControlSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.LoopOrientationSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.LoopFleetRecoverySelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.LoopRecoverySafetySelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.RecoveryCircuitStateSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.YLoopRecoverySelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.YTurnbackSafetySelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.YTurnbackQueueSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.StationTurnbackSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.OpposedYLoopSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.LoopExitRecoveryStateSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.LoopEntranceClearanceSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.StationQueueHoldSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.ScheduledServicesSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.FreightStopsSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.FreightTransfersSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.RouteStopServiceSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.SteamRockDrillsSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.SteamRockDrillServiceSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.SteamYardersSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.SteamYarderServiceSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.SteamSuppliesSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.SteamWaterPumpsSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.SteamWaterPumpServiceSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.WaterFillStopsSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($classesDir + [IO.Path]::PathSeparator + $testClassesDir), 'org.wurmrail.core.WaterUnloadStopsSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $testClasspath), 'org.wurmrail.RailwayWaterUnloadServiceSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $testClasspath), 'org.wurmrail.shared.CoalVeinTilesSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $testClasspath), 'org.wurmrail.shared.RailConsistFrameSelfTest')
Write-Host 'Checking railway builder input, captured identities and forged form answers.'
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $testClasspath), 'org.wurmrail.RailwayBuilderUiSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $testClasspath), 'org.wurmrail.RailwaySignalUiSelfTest')
Write-Host 'Checking combined station identities, migration, connectivity and destination form answers.'
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $testClasspath), 'org.wurmrail.core.PassengerStationsSelfTest')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $testClasspath), 'org.wurmrail.RailwayPassengerUiSelfTest')
$bmlClasspath = $testClasspath + [IO.Path]::PathSeparator + (Join-Path $clientPath 'client.jar')
Invoke-JavaTool $toolchain.Compiler ($targetArgs + @('-encoding', 'UTF-8', '-cp', $bmlClasspath,
    '-d', $testClassesDir, (Join-Path $projectRoot 'tools/RailwayBmlClientSmoke.java'), (Join-Path $projectRoot 'tools/RailwayFreightUiSmoke.java'), (Join-Path $projectRoot 'tools/BulkHopperUiSmoke.java'), (Join-Path $projectRoot 'tools/SteamRockDrillUiSmoke.java'), (Join-Path $projectRoot 'tools/IndustrialWaterUiSmoke.java'), (Join-Path $projectRoot 'tools/LogBunkUiSmoke.java'), (Join-Path $projectRoot 'tools/SteamYarderUiSmoke.java'), (Join-Path $projectRoot 'tools/SteamWaterPumpUiSmoke.java'), (Join-Path $projectRoot 'tools/SteamSupplyUiSmoke.java')))
Write-Host 'Checking all railway windows with the shipped Wurm client BML parser.'
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $bmlClasspath), 'RailwayBmlClientSmoke')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $bmlClasspath), 'RailwayFreightUiSmoke')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $bmlClasspath), 'BulkHopperUiSmoke')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $bmlClasspath), 'SteamRockDrillUiSmoke')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $bmlClasspath), 'IndustrialWaterUiSmoke')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $bmlClasspath), 'LogBunkUiSmoke')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $bmlClasspath), 'SteamYarderUiSmoke')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $bmlClasspath), 'SteamWaterPumpUiSmoke')
Invoke-JavaTool $compatRuntime @('-ea', '-cp', ($testClassesDir + [IO.Path]::PathSeparator + $bmlClasspath), 'SteamSupplyUiSmoke')

$packageModDir = Join-Path $stagingDir 'mods/wurmrail'
New-Item -ItemType Directory -Path $packageModDir -Force | Out-Null
$manifestPath = Join-Path $projectRoot 'build/MANIFEST.MF'
[IO.File]::WriteAllText($manifestPath, "Manifest-Version: 1.0`r`nImplementation-Title: WurmRail`r`nImplementation-Version: $Version`r`n`r`n", $utf8NoBom)
$jarPath = Join-Path $packageModDir 'wurmrail.jar'
Invoke-JavaTool $toolchain.Jar @('cfm', $jarPath, $manifestPath, '-C', $classesDir, '.')
# The packaged jar must pass actual discovery before gameplay classes load.
# The old test constructed the mod directly and could not catch frozen Player.
$startupLog = Join-Path $projectRoot 'build/startup-integration.log'
Write-Host 'Checking packaged jar through real modloader discovery and native integration.'
try {
    & (Join-Path $projectRoot 'tools/smoke.ps1') `
        -JavaHome (Split-Path (Split-Path $toolchain.Compiler -Parent) -Parent) `
        -ServerDir $serverPath -ModLoaderDir $loaderPath -ModJar $jarPath *> $startupLog
    if ($LASTEXITCODE -ne 0) { throw 'Native startup regression failed.' }
} catch {
    if (Test-Path -LiteralPath $startupLog) { Get-Content -LiteralPath $startupLog -Tail 35 }
    throw
}
Write-Host "Real modloader startup and native integration passed. Log: $startupLog"

if ($UseExistingAssets) {
    Write-Host 'Using existing generated models; structural and native validation remain required.'
} else {
    Write-Host 'Generating original vehicle meshes and eight-direction track layouts.'
    & $Python (Join-Path $projectRoot 'tools/generate_models.py')
    if ($LASTEXITCODE -ne 0) { throw 'Custom model generation failed.' }
}
& $Python (Join-Path $projectRoot 'tools/validate_assets.py')
if ($LASTEXITCODE -ne 0) { throw 'Custom model structural validation failed.' }
$clientPackageDir = Join-Path $stagingDir 'client'
New-Item -ItemType Directory -Path (Join-Path $clientPackageDir 'mods/wurmrailmodels'),(Join-Path $clientPackageDir 'packs') -Force | Out-Null
$clientSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/client/java') -Recurse -Filter '*.java' -File | ForEach-Object FullName) + $sharedSources
if ($clientSources.Count -eq 0) { throw 'No client support sources found.' }
$clientArgsFile = Join-Path $projectRoot 'build/client-sources.txt'
[IO.File]::WriteAllLines($clientArgsFile, @($clientSources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), $utf8NoBom)
$clientNativeJar = Join-Path $clientPath 'client-patched.jar'
if (-not (Test-Path -LiteralPath $clientNativeJar -PathType Leaf)) { $clientNativeJar = Join-Path $clientPath 'client.jar' }
$clientDependencies = @((Join-Path $clientLoaderPath 'modlauncher.jar'),(Join-Path $clientLoaderPath 'javassist.jar'),
    $clientNativeJar,(Join-Path $clientPath 'common.jar'))
$clientDependencies += @(Get-ChildItem -LiteralPath (Join-Path $clientPath 'lib') -Filter '*.jar' -File | ForEach-Object FullName)
$clientClasspath = $clientDependencies -join [IO.Path]::PathSeparator
Invoke-JavaTool $toolchain.Compiler ($targetArgs + @('-encoding','UTF-8','-cp',$clientClasspath,'-d',$clientClassesDir,"@$clientArgsFile"))
Invoke-JavaTool $toolchain.Compiler ($targetArgs + @('-encoding','UTF-8','-cp',$clientClassesDir,'-d',$testClassesDir,
    (Join-Path $projectRoot 'tools/RailVehicleMotionSelfTest.java')))
Write-Host 'Checking buffered client vehicle curves, backing, heights, packet cadence and stopped motion.'
Invoke-JavaTool $compatRuntime @('-ea','-cp',($testClassesDir + [IO.Path]::PathSeparator + $clientClassesDir),'RailVehicleMotionSelfTest')
Invoke-JavaTool $toolchain.Jar @('cfm',(Join-Path $clientPackageDir 'mods/wurmrailmodels/wurmrailmodels.jar'),$manifestPath,'-C',$clientClassesDir,'.')
Copy-Item -LiteralPath (Join-Path $projectRoot 'client/mods/wurmrailmodels.properties') -Destination (Join-Path $clientPackageDir 'mods/wurmrailmodels.properties')
Copy-Item -LiteralPath (Join-Path $projectRoot 'assets/wurmrail-models.jar') -Destination (Join-Path $clientPackageDir 'packs/wurmrail-models.jar')
$clientLog = Join-Path $projectRoot 'build/client-model-integration.log'
Write-Host 'Checking packaged client mod through genuine discovery and native Collada parsing.'
try {
    & (Join-Path $projectRoot 'tools/client-smoke.ps1') `
        -JavaHome (Split-Path (Split-Path $toolchain.Compiler -Parent) -Parent) `
        -ClientDir $clientPath -ClientModLoaderDir $clientLoaderPath -PackageDir $clientPackageDir *> $clientLog
    if ($LASTEXITCODE -ne 0) { throw 'Native client model regression failed.' }
} catch {
    if (Test-Path -LiteralPath $clientLog) { Get-Content -LiteralPath $clientLog -Tail 45 }
    throw
}
Write-Host "Native client mod startup and model import passed. Log: $clientLog"
Copy-Item -LiteralPath (Join-Path $projectRoot 'mods/wurmrail.properties') -Destination (Join-Path $stagingDir 'mods/wurmrail.properties')
foreach ($document in @('README.md', 'QUICKSTART.md', 'TRESTLES.md', 'CHANGELOG.md', 'THIRD-PARTY.md', 'COAL-MAP-HANDOFF.md', 'Install-WurmRail.ps1','Install-WurmRailClient.ps1')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $document) -Destination (Join-Path $stagingDir $document)
}
$distDir = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $distDir -Force | Out-Null
$releasePath = Join-Path $distDir "WurmRail-$Version.zip"
Compress-Archive -Path (Join-Path $stagingDir '*') -DestinationPath $releasePath -Force
$releaseHash = (Get-FileHash -LiteralPath $releasePath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($releasePath + '.sha256'), "$releaseHash  WurmRail-$Version.zip`n", $utf8NoBom)
$serverStaging = Join-Path $projectRoot 'build/server-package'
Reset-BuildDirectory $serverStaging
Copy-Item -LiteralPath (Join-Path $stagingDir 'mods') -Destination $serverStaging -Recurse
foreach ($document in @('README.md', 'QUICKSTART.md', 'TRESTLES.md', 'CHANGELOG.md', 'THIRD-PARTY.md', 'COAL-MAP-HANDOFF.md', 'Install-WurmRail.ps1')) {
    Copy-Item -LiteralPath (Join-Path $stagingDir $document) -Destination (Join-Path $serverStaging $document)
}
$serverReleasePath = Join-Path $distDir "WurmRail-$Version-server.zip"
Compress-Archive -Path (Join-Path $serverStaging '*') -DestinationPath $serverReleasePath -Force
$serverReleaseHash = (Get-FileHash -LiteralPath $serverReleasePath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($serverReleasePath + '.sha256'), "$serverReleaseHash  WurmRail-$Version-server.zip`n", $utf8NoBom)
$standaloneJar = Join-Path $distDir "wurmrail-$Version.jar"
Copy-Item -LiteralPath $jarPath -Destination $standaloneJar -Force
$jarHash = (Get-FileHash -LiteralPath $standaloneJar -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($standaloneJar + '.sha256'), "$jarHash  wurmrail-$Version.jar`n", $utf8NoBom)
$clientStaging = Join-Path $projectRoot 'build/client-package'
Reset-BuildDirectory $clientStaging
Copy-Item -LiteralPath (Join-Path $stagingDir 'client') -Destination $clientStaging -Recurse
foreach ($document in @('README.md','QUICKSTART.md','TRESTLES.md','CHANGELOG.md','THIRD-PARTY.md','COAL-MAP-HANDOFF.md','Install-WurmRailClient.ps1')) {
    Copy-Item -LiteralPath (Join-Path $stagingDir $document) -Destination (Join-Path $clientStaging $document)
}
$clientReleasePath = Join-Path $distDir "WurmRail-$Version-client.zip"
Compress-Archive -Path (Join-Path $clientStaging '*') -DestinationPath $clientReleasePath -Force
$clientReleaseHash = (Get-FileHash -LiteralPath $clientReleasePath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($clientReleasePath + '.sha256'), "$clientReleaseHash  WurmRail-$Version-client.zip`n", $utf8NoBom)
Write-Host 'Checking shareable archive contents, checksums and tested mod classes.'
& $Python (Join-Path $projectRoot 'tools/verify_release.py') $Version
if ($LASTEXITCODE -ne 0) { throw 'Shareable release verification failed.' }
Write-Host "Built $releasePath"
Write-Host "SHA-256: $releaseHash"
Write-Host "Server-only update: $serverReleasePath"
Write-Host "Server SHA-256: $serverReleaseHash"
Write-Host "Client update: $clientReleasePath"
Write-Host "Client SHA-256: $clientReleaseHash"
Write-Host 'The package contains both server and client mods plus original art; no proprietary game jars or modloader binaries.'
