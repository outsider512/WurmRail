[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [string]$ClientDir,
    [string]$PackageDir = $PSScriptRoot,
    [switch]$InstallModLoader
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$loaderUrl = 'https://github.com/ago1024/WurmClientModLauncher/releases/download/v0.15/client-modlauncher-0.15.zip'
$loaderHash = '9d19936c8a35bc3c557caba0474b6945bb7bbadd126e23fc133a0accbf6fbb49'
$loaderFiles = @('modlauncher.jar', 'javassist.jar', 'patcher.jar', 'patcher.bat', 'patcher.sh', 'logging.properties')

function Assert-ContainedPath([string]$Root, [string]$Path) {
    $absoluteRoot = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $absolutePath = [IO.Path]::GetFullPath($Path)
    if (-not $absolutePath.StartsWith($absoluteRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path must remain inside $absoluteRoot`: $absolutePath"
    }
    return $absolutePath
}

function Assert-ClientStopped {
    try { $processes = @(Get-CimInstance Win32_Process -ErrorAction Stop) }
    catch { throw 'Cannot inspect running processes. Close the client and follow README manual installation steps.' }
    $running = @($processes | Where-Object {
        $_.Name -match '^WurmLauncher(?:\.exe)?$' -or
        ($_.CommandLine -and $_.Name -match '^(?:java|javaw)(?:\.exe)?$' -and
            $_.CommandLine -match '(?:client(?:-patched)?\.jar|com\.wurmonline\.client|org\.gotti\.wurmunlimited\.clientlauncher)')
    })
    if ($running.Count -gt 0) {
        $processNames = ($running | ForEach-Object { "$($_.Name) (PID $($_.ProcessId))" }) -join ', '
        throw "Close all Wurm game clients on this computer before installation: $processNames"
    }
}

if ($env:OS -ne 'Windows_NT') { throw 'This installer uses Windows process checks. Follow README manual installation on Linux.' }
$clientPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $ClientDir).Path)
if ($clientPath.TrimEnd('\', '/') -eq [IO.Path]::GetPathRoot($clientPath).TrimEnd('\', '/')) { throw 'ClientDir cannot be a drive root.' }
foreach ($required in @('client.jar', 'common.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $clientPath $required) -PathType Leaf)) {
        throw "ClientDir must be the WurmLauncher client directory containing $required."
    }
}
$packagePath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $PackageDir).Path)
$clientFiles = @('mods/wurmrailmodels.properties', 'mods/wurmrailmodels/wurmrailmodels.jar', 'packs/wurmrail-models.jar')
foreach ($relativePath in $clientFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $packagePath ('client/' + $relativePath)) -PathType Leaf)) {
        throw "Package file missing: client/$relativePath. Extract the complete WurmRail release first."
    }
}
if ($packagePath.TrimEnd('\', '/') -eq $clientPath.TrimEnd('\', '/')) { throw 'Extract the release into a separate directory before installation.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$clientArchive = [IO.Compression.ZipFile]::OpenRead((Join-Path $clientPath 'client.jar'))
try { $unpatched = $null -ne $clientArchive.GetEntry('com/wurmonline/client/WurmClientBase.class') }
finally { $clientArchive.Dispose() }
if (-not $unpatched -and -not (Test-Path -LiteralPath (Join-Path $clientPath 'client-patched.jar') -PathType Leaf)) {
    throw 'client.jar has no native game classes and client-patched.jar is missing. Restore or verify your Wurm client first.'
}
if (-not $InstallModLoader) {
    foreach ($required in @('modlauncher.jar', 'javassist.jar')) {
        if (-not (Test-Path -LiteralPath (Join-Path $clientPath $required) -PathType Leaf)) {
            throw "Existing CLIENT modloader not found ($required). Use -InstallModLoader to install pinned official v0.15."
        }
    }
    if ($unpatched) { throw 'The client modloader is not activated. Use -InstallModLoader to install and run its official patcher.' }
}
$runtime = Join-Path $clientPath '../runtime/bin/java.exe'
if ($InstallModLoader -and -not (Test-Path -LiteralPath $runtime -PathType Leaf)) {
    throw 'The game bundled Java runtime ../runtime/bin/java.exe is missing. Use README manual client installation.'
}
Assert-ClientStopped
if (-not $PSCmdlet.ShouldProcess($clientPath, 'Back up and install WurmRail custom models and client support')) { return }

$temporaryPath = Join-Path ([IO.Path]::GetTempPath()) ('WurmRail-client-install-' + [Guid]::NewGuid().ToString('N'))
$backupPath = Assert-ContainedPath $clientPath (Join-Path $clientPath ('wurmrail-backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')))
$writeOperations = New-Object 'System.Collections.Generic.List[object]'
try {
    if ($InstallModLoader) {
        New-Item -ItemType Directory -Path $temporaryPath -Force | Out-Null
        $archivePath = Assert-ContainedPath $temporaryPath (Join-Path $temporaryPath 'client-modloader.zip')
        Write-Host 'Downloading official WurmClientModLauncher v0.15.'
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $loaderUrl -OutFile $archivePath -UseBasicParsing
        if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $loaderHash) {
            throw 'Client modloader checksum mismatch; no game files were modified.'
        }
        $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
        try {
            foreach ($filename in $loaderFiles) {
                $entry = $archive.GetEntry($filename)
                if ($null -eq $entry) { throw "Official client modloader archive is missing $filename." }
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Assert-ContainedPath $temporaryPath (Join-Path $temporaryPath $filename)), $false)
            }
        } finally { $archive.Dispose() }
    }
    Assert-ClientStopped
    $relativeFiles = $clientFiles
    if ($InstallModLoader) { $relativeFiles += $loaderFiles + @('client.jar', 'client-patched.jar') }
    New-Item -ItemType Directory -Path $backupPath -Force | Out-Null
    foreach ($relativePath in $relativeFiles) {
        $target = Assert-ContainedPath $clientPath (Join-Path $clientPath $relativePath)
        if (Test-Path -LiteralPath $target -PathType Container) { throw "Expected a file but found a directory: $target" }
        $existed = Test-Path -LiteralPath $target -PathType Leaf
        $savedFile = Assert-ContainedPath $backupPath (Join-Path $backupPath $relativePath)
        if ($existed) {
            New-Item -ItemType Directory -Path (Split-Path $savedFile -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $target -Destination $savedFile
        }
        $source = if ($loaderFiles -contains $relativePath) { Join-Path $temporaryPath $relativePath }
                  elseif ($clientFiles -contains $relativePath) { Join-Path $packagePath ('client/' + $relativePath) }
                  else { $null }
        $writeOperations.Add([pscustomobject]@{ RelativePath = $relativePath; Source = $source; Target = $target; Backup = $savedFile; Existed = $existed; Written = $false })
    }
    $writeOperations | Select-Object RelativePath,Existed | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backupPath 'manifest.json') -Encoding UTF8
    foreach ($operation in $writeOperations) {
        if ($null -eq $operation.Source) { continue }
        New-Item -ItemType Directory -Path (Split-Path $operation.Target -Parent) -Force | Out-Null
        $operation.Written = $true
        Copy-Item -LiteralPath $operation.Source -Destination $operation.Target -Force
    }
    if ($InstallModLoader) {
        # The upstream patcher preserves native classes in client-patched.jar and
        # replaces client.jar with the loader. Cover both writes in rollback.
        foreach ($operation in $writeOperations) {
            if (@('client.jar','client-patched.jar') -contains $operation.RelativePath) { $operation.Written = $true }
        }
        Push-Location -LiteralPath $clientPath
        try {
            & $runtime -classpath 'patcher.jar;javassist.jar' org.gotti.wurmunlimited.patcher.PatchClientJar
            if ($LASTEXITCODE -ne 0) { throw "Official client patcher failed with exit code $LASTEXITCODE." }
        } finally { Pop-Location }
    }
    Write-Host "Installed WurmRail client models in $clientPath"
    Write-Host "Backup: $backupPath"
    Write-Host 'Start Wurm Unlimited through Steam as usual. Install this client update on every player computer.'
    Write-Host 'The matching WurmRail server update enables bridge track construction. Fully restart each client after installing.'
} catch {
    $installFailure = $_
    foreach ($operation in $writeOperations) {
        if ($operation.Written) {
            try {
                if ($operation.Existed) { Copy-Item -LiteralPath $operation.Backup -Destination $operation.Target -Force }
                elseif (Test-Path -LiteralPath $operation.Target -PathType Leaf) { Remove-Item -LiteralPath $operation.Target -Force }
            } catch { Write-Warning "Could not restore $($operation.Target); original backup is in $backupPath." }
        }
    }
    throw $installFailure
} finally {
    if (Test-Path -LiteralPath $temporaryPath) {
        $resolvedTemp = [IO.Path]::GetFullPath($temporaryPath)
        $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
        if ($resolvedTemp.StartsWith($expectedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($resolvedTemp).StartsWith('WurmRail-client-install-')) {
            Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
        }
    }
}
