[CmdletBinding()]
param(
    [string]$JavaHome = 'C:/Program Files/Android/Android Studio/jbr',
    [string]$ClientDir = (Join-Path $PSScriptRoot '../Wurm Unlimited/WurmLauncher'),
    [string]$ClientModLoaderDir = (Join-Path $PSScriptRoot 'clientloader'),
    [string]$PackageDir = (Join-Path $PSScriptRoot '../build/package/client')
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$clientPath = (Resolve-Path -LiteralPath $ClientDir).Path
$loaderPath = (Resolve-Path -LiteralPath $ClientModLoaderDir).Path
$packagePath = (Resolve-Path -LiteralPath $PackageDir).Path
$smokeRoot = Join-Path $taskRoot ('build/client-smoke-' + [Guid]::NewGuid().ToString('N'))
$smokeClasses = Join-Path $smokeRoot 'classes'
New-Item -ItemType Directory -Path $smokeClasses -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $packagePath 'mods'),(Join-Path $packagePath 'packs') -Destination $smokeRoot -Recurse
# A patched client keeps the game classes in client-patched.jar.
$nativeJar = Join-Path $clientPath 'client-patched.jar'
if (-not (Test-Path -LiteralPath $nativeJar -PathType Leaf)) { $nativeJar = Join-Path $clientPath 'client.jar' }
$dependencies = @($smokeClasses, $nativeJar, (Join-Path $clientPath 'common.jar'),
    (Join-Path $loaderPath 'modlauncher.jar'), (Join-Path $loaderPath 'javassist.jar'))
$dependencies += @(Get-ChildItem -LiteralPath (Join-Path $clientPath 'lib') -Filter '*.jar' -File | ForEach-Object FullName)
$classpath = $dependencies -join [IO.Path]::PathSeparator
$compiler = Join-Path $JavaHome 'bin/javac.exe'
$runtime = Join-Path $clientPath '../runtime/bin/java.exe'
if (-not (Test-Path -LiteralPath $runtime)) { $runtime = Join-Path $JavaHome 'bin/java.exe' }
$compilerVersion = (& $compiler -version 2>&1 | Out-String).Trim()
$target = if ($compilerVersion -match 'javac 1\.8\.') { @('-source','1.8','-target','1.8') } else { @('--release','8') }
$compileClasspath = (Join-Path $packagePath 'mods/wurmrailmodels/wurmrailmodels.jar') + [IO.Path]::PathSeparator + $classpath
$smokeSources = @('RailVehicleColdLoadSmoke.java','ClientModelsSmoke.java','CoalVeinClientSmoke.java','RailTrackTerrainClientSmoke.java','RailVehicleTerrainClientSmoke.java','RailVehicleMotionClientSmoke.java','RailVehicleRiderClientSmoke.java','RailVehiclePlayerClientSmoke.java','RailVehicleClockClientSmoke.java','RailVehicleRefreshClientSmoke.java','RailConsistHandshakeClientSmoke.java','TrestleMapClientSmoke.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
$smokeSources += @('TrestleAlignment.java','TrestleHermite.java') | ForEach-Object {
    Join-Path $taskRoot "src/main/java/org/wurmrail/core/$_"
}
& $compiler @target -encoding UTF-8 -cp $compileClasspath -d $smokeClasses @smokeSources
if ($LASTEXITCODE -ne 0) { throw 'Client model smoke harness compilation failed.' }
Push-Location -LiteralPath $smokeRoot
try {
    # A separate JVM must reach its first rail packet after bootstrap references
    # have gone away; the warm model suite intentionally retains its mod list.
    & $runtime '-Djava.awt.headless=true' ('-Djava.library.path=' + (Join-Path $clientPath 'nativelibs')) `
        -ea -cp $classpath RailVehicleColdLoadSmoke 2>&1 | Tee-Object -FilePath (Join-Path $smokeRoot 'cold-load.log')
    if ($LASTEXITCODE -ne 0) { throw "Cold client helper loading failed. See $smokeRoot/cold-load.log" }
    & $runtime '-Djava.awt.headless=true' ('-Djava.library.path=' + (Join-Path $clientPath 'nativelibs')) `
        -ea -cp $classpath ClientModelsSmoke $smokeRoot 2>&1 | Tee-Object -FilePath (Join-Path $smokeRoot 'client-smoke.log')
    if ($LASTEXITCODE -ne 0) { throw "Native client model smoke failed. See $smokeRoot/client-smoke.log" }
    Write-Host "Client smoke artifacts retained at $smokeRoot"
} finally { Pop-Location }
