[CmdletBinding()]
param(
    [string]$JavaHome = 'C:/Program Files/Android/Android Studio/jbr',
    [string]$ServerDir = (Join-Path $PSScriptRoot '../Wurm Unlimited/WurmServerLauncher'),
    [string]$ModLoaderDir = (Join-Path $PSScriptRoot 'modloader'),
    [string]$ModJar = (Join-Path $PSScriptRoot '../build/package/mods/wurmrail/wurmrail.jar')
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$serverPath = (Resolve-Path -LiteralPath $ServerDir).Path
$loaderPath = (Resolve-Path -LiteralPath $ModLoaderDir).Path
$modJarPath = [IO.Path]::GetFullPath($ModJar)
if (-not (Test-Path -LiteralPath $modJarPath -PathType Leaf)) {
    throw 'Build the mod with build.ps1 before running the integration smoke test.'
}
$smokeRoot = Join-Path $taskRoot ('build/smoke-' + [Guid]::NewGuid().ToString('N'))
$smokeClasses = Join-Path $smokeRoot 'classes'
$smokeWorld = Join-Path $smokeRoot 'world'
New-Item -ItemType Directory -Path $smokeClasses,(Join-Path $smokeWorld 'sqlite') -Force | Out-Null
$fixtureModDir = Join-Path $smokeWorld 'mods/wurmrail'
New-Item -ItemType Directory -Path $fixtureModDir -Force | Out-Null
Copy-Item -LiteralPath $modJarPath -Destination (Join-Path $fixtureModDir 'wurmrail.jar')
Copy-Item -LiteralPath (Join-Path $taskRoot 'mods/wurmrail.properties') -Destination (Join-Path $smokeWorld 'mods/wurmrail.properties')
# Copy only pristine distribution databases; never open or change an existing server world.
$sourceDbDir = Join-Path $serverPath 'dist/Creative/sqlite'
if (Test-Path -LiteralPath $sourceDbDir) {
    Get-ChildItem -LiteralPath $sourceDbDir -Filter '*.db' -File | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $smokeWorld 'sqlite')
    }
}
$sourceConfig = Join-Path $serverPath 'dist/Creative/wurm.ini'
if (Test-Path -LiteralPath $sourceConfig) {
    Copy-Item -LiteralPath $sourceConfig -Destination (Join-Path $smokeWorld 'wurm.ini')
}
$dependencies = @($smokeClasses, (Join-Path $serverPath 'server.jar'),
    (Join-Path $serverPath 'common.jar'), (Join-Path $loaderPath 'modlauncher.jar'),
    (Join-Path $loaderPath 'javassist.jar'))
$libPath = Join-Path $serverPath 'lib'
if (Test-Path -LiteralPath $libPath) {
    $dependencies += @(Get-ChildItem -LiteralPath $libPath -Filter '*.jar' -File | ForEach-Object FullName)
}
# Runtime deliberately excludes build/classes and the mod jar. The modloader must
# discover and add exactly the packaged jar from the isolated mods directory.
$classpath = $dependencies -join [IO.Path]::PathSeparator
$compileClasspath = $modJarPath + [IO.Path]::PathSeparator + $classpath
$compiler = Join-Path $JavaHome 'bin/javac.exe'
$runtime = Join-Path $serverPath '../runtime/bin/java.exe'
if (-not (Test-Path -LiteralPath $runtime)) { $runtime = Join-Path $JavaHome 'bin/java.exe' }
$compilerVersion = (& $compiler -version 2>&1 | Out-String).Trim()
$target = if ($compilerVersion -match 'javac 1\.8\.') { @('-source','1.8','-target','1.8') } else { @('--release','8') }
$smokeSources = @('IntegrationSmoke.java', 'UsabilitySmoke.java', 'TrainUiAnswersSmoke.java',
    'RailwayUiSmoke.java', 'TrackMaterialsSmoke.java', 'RailwayRuntimeSmoke.java', 'TrackCornerRuntimeSmoke.java', 'TrackSlopeRuntimeSmoke.java', 'RailVegetationRuntimeSmoke.java',
    'PassengerWorldSmoke.java', 'PassengerServiceSmoke.java', 'PassengerRuntimeSmoke.java', 'CombinedStationRuntimeSmoke.java',
    'JunctionWorldSmoke.java', 'JunctionRuntimeSmoke.java', 'SignalWorldSmoke.java', 'SignalRuntimeSmoke.java', 'PlatformRouteSmoke.java',
    'JunctionSignalLoopSmoke.java', 'TrafficRuntimeSmoke.java', 'ConvoyRuntimeSmoke.java', 'QueueTurnRuntimeSmoke.java',
    'CaveEntranceRuntimeSmoke.java', 'RailVehicleTerrainServerSmoke.java', 'RailwayBridgeRuntimeSmoke.java', 'CaveBuilderRuntimeSmoke.java', 'ScheduledRuntimeSmoke.java', 'JamRecoveryRuntimeSmoke.java', 'YLoopRuntimeSmoke.java', 'StationTurnbackRuntimeSmoke.java', 'OpposedYLoopRuntimeSmoke.java', 'StationQueueHoldRuntimeSmoke.java', 'FreightRuntimeSmoke.java', 'BulkHopperRuntimeSmoke.java', 'FreightFillRuntimeSmoke.java', 'SteamDrillMiningRuntimeSmoke.java', 'SteamDrillRuntimeSmoke.java', 'WaterFillNativeRuntimeSmoke.java', 'WaterFillRuntimeSmoke.java', 'IndustrialWaterRuntimeSmoke.java', 'IndustrialWaterRecoveryRuntimeSmoke.java', 'LogBunkRuntimeSmoke.java', 'SteamYarderNativeRuntimeSmoke.java', 'SteamYarderRuntimeSmoke.java', 'LogCarRuntimeSmoke.java', 'LogCarFreightRuntimeSmoke.java', 'LiquidFreightRuntimeSmoke.java', 'TankCarCapacityRuntimeSmoke.java', 'SteamWaterPumpNativeRuntimeSmoke.java', 'DeprecatedWaterFillRuntimeSmoke.java', 'CoalVeinNativeRuntimeSmoke.java', 'CoalDrillRuntimeSmoke.java', 'CoalLogisticsRuntimeSmoke.java', 'SteamTestSupplies.java', 'SteamConsumptionRuntimeSmoke.java') |
    ForEach-Object { Join-Path $PSScriptRoot $_ }
& $compiler @target -encoding UTF-8 -cp $compileClasspath -d $smokeClasses @smokeSources
if ($LASTEXITCODE -ne 0) { throw 'Integration smoke harness compilation failed.' }
Push-Location -LiteralPath $smokeWorld
try {
    $logPath = Join-Path $smokeRoot 'smoke.log'
    & $runtime '-Djava.awt.headless=true' ('-Djava.library.path=' + (Join-Path $serverPath 'nativelibs')) `
        -ea -cp $classpath IntegrationSmoke $smokeWorld 2>&1 | Tee-Object -FilePath $logPath
    if ($LASTEXITCODE -ne 0) { throw "Integration smoke test failed. See $logPath" }
    Write-Host "Smoke artifacts retained at $smokeRoot"
} finally { Pop-Location }
