[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerDir,
    [string]$PackageDir = $PSScriptRoot,
    [switch]$InstallModLoader,
    [switch]$ReplaceConfig
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$loaderUrl = 'https://github.com/ago1024/WurmServerModLauncher/releases/download/v0.47/server-modlauncher-0.47.zip'
$loaderHash = '293554a85cee915585c46f1129609bfa01165b319b81ff7ef3bfffa1b24f8aac'
$loaderFiles = @('modlauncher.jar', 'javassist.jar', 'modlauncher.bat', 'modlauncher.sh', 'patcher.jar', 'patcher.bat', 'patcher.sh', 'logging.properties')

function Assert-ContainedPath([string]$Root, [string]$Path) {
    $absoluteRoot = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $absolutePath = [IO.Path]::GetFullPath($Path)
    if (-not $absolutePath.StartsWith($absoluteRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path must remain inside $absoluteRoot`: $absolutePath"
    }
    return $absolutePath
}

function Assert-ServerStopped {
    # The official modlauncher sets -Dworkdir, but launchers can also use relative
    # classpaths. Conservatively reject any Wurm server process on this host.
    try { $processes = @(Get-CimInstance Win32_Process -ErrorAction Stop) }
    catch { throw 'Cannot inspect running processes. Run the installer on Windows with process-query permission; see README for manual installation.' }
    $running = @($processes | Where-Object {
        $_.Name -match '^WurmServerLauncher(?:\.exe)?$' -or
        ($_.CommandLine -and $_.Name -match '^(?:java|javaw)(?:\.exe)?$' -and $_.CommandLine -match '(?:modlauncher\.jar|server\.jar|com\.wurmonline\.server)')
    })
    if ($running.Count -gt 0) {
        $processNames = ($running | ForEach-Object { "$($_.Name) (PID $($_.ProcessId))" }) -join ', '
        throw "Stop all Wurm server processes on this computer before installation: $processNames"
    }
}

function Update-WurmRailDefaultSpeed([AllowEmptyString()][string]$Text) {
    # Parse logical Java-properties lines without reserializing the configuration.
    # Retain each character's source offset so even continuations and byte encoding survive.
    $logical = New-Object Text.StringBuilder
    $positions = New-Object 'System.Collections.Generic.List[int]'
    $continued = $false
    $replacementPositions = $null
    foreach ($physical in [regex]::Matches($Text, '[^\r\n]*(?:\r\n|\r|\n|$)')) {
        if ($physical.Length -eq 0) { continue }
        $line = [regex]::Replace($physical.Value, '[\r\n]+$', '')
        $start = 0
        if (-not $continued) {
            [void]$logical.Clear()
            $positions.Clear()
            if ($physical.Index -eq 0) {
                if ($line.StartsWith([string][char]0xfeff, [StringComparison]::Ordinal)) { $start = 1 }
                elseif ($line.StartsWith(([string][char]0xef + [char]0xbb + [char]0xbf), [StringComparison]::Ordinal)) { $start = 3 }
            }
            if ($line.Substring($start) -match '^[ \t\f]*[#!]') { continue }
        }
        if ($continued) {
            while ($start -lt $line.Length -and ' ', "`t", "`f" -ccontains $line[$start].ToString()) { $start++ }
        }
        $slashes = 0
        for ($index = $line.Length - 1; $index -ge 0 -and $line[$index] -eq '\'; $index--) { $slashes++ }
        $joinsNextLine = ($slashes % 2) -eq 1
        $end = $line.Length - [int]$joinsNextLine
        for ($index = $start; $index -lt $end; $index++) {
            [void]$logical.Append($line[$index])
            $positions.Add($physical.Index + $index)
        }
        $continued = $joinsNextLine -and ($physical.Index + $physical.Length -lt $Text.Length)
        if ($continued) { continue }
        $property = $logical.ToString()
        $keyStart = 0
        while ($keyStart -lt $property.Length -and ' ', "`t", "`f" -ccontains $property[$keyStart].ToString()) { $keyStart++ }
        $keyEnd = $keyStart
        while ($keyEnd -lt $property.Length) {
            if ($property[$keyEnd] -eq '\') { $keyEnd += 2; continue }
            if ('=', ':', ' ', "`t", "`f" -ccontains $property[$keyEnd].ToString()) { break }
            $keyEnd++
        }
        $keyEnd = [Math]::Min($keyEnd, $property.Length)
        $rawKey = $property.Substring($keyStart, $keyEnd - $keyStart)
        $key = [regex]::Replace($rawKey, '\\(u[0-9a-fA-F]{4}|.)', {
            param($escape)
            $value = $escape.Groups[1].Value
            if ($value.Length -eq 5 -and $value[0] -ceq 'u') { return [char][Convert]::ToInt32($value.Substring(1), 16) }
            switch -CaseSensitive ($value) {
                't' { return "`t" }; 'r' { return "`r" }; 'n' { return "`n" }; 'f' { return "`f" }
                default { return $value }
            }
        })
        if ($key -cne 'maxSpeed') { continue }
        # The final effective value wins, even if an escaped key/value is left untouched.
        $replacementPositions = $null
        if ($rawKey -cne 'maxSpeed') { continue }
        $valueStart = $keyEnd
        while ($valueStart -lt $property.Length -and ' ', "`t", "`f" -ccontains $property[$valueStart].ToString()) { $valueStart++ }
        if ($valueStart -lt $property.Length -and '=', ':' -ccontains $property[$valueStart].ToString()) { $valueStart++ }
        while ($valueStart -lt $property.Length -and ' ', "`t", "`f" -ccontains $property[$valueStart].ToString()) { $valueStart++ }
        $number = [regex]::Match($property.Substring($valueStart), '^[ \t\f]*(?<value>[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?[dDfF]?)[ \t\f]*$')
        $parsed = 0.0
        if (-not $number.Success -or -not [double]::TryParse(($number.Groups['value'].Value -replace '[dDfF]$', ''),
                [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed) -or $parsed -ne 3.0) { continue }
        $replacementPositions = @()
        $token = $number.Groups['value']
        for ($index = 0; $index -lt $token.Length; $index++) { $replacementPositions += $positions[$valueStart + $token.Index + $index] }
    }
    if ($null -eq $replacementPositions) { return $Text }
    $updated = New-Object Text.StringBuilder -ArgumentList $Text
    for ($index = $replacementPositions.Count - 1; $index -ge 0; $index--) { [void]$updated.Remove($replacementPositions[$index], 1) }
    [void]$updated.Insert($replacementPositions[0], '5.0')
    return $updated.ToString()
}

if ($env:OS -ne 'Windows_NT') { throw 'This installer uses Windows process checks. Follow README manual installation steps on Linux.' }
$serverPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $ServerDir).Path)
if ($serverPath.TrimEnd('\', '/') -eq [IO.Path]::GetPathRoot($serverPath).TrimEnd('\', '/')) { throw 'ServerDir cannot be a drive root.' }
foreach ($required in @('server.jar', 'common.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $serverPath $required) -PathType Leaf)) { throw "ServerDir must be the WurmServerLauncher directory containing $required." }
}
$packagePath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $PackageDir).Path)
foreach ($required in @('mods/wurmrail.properties', 'mods/wurmrail/wurmrail.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $packagePath $required) -PathType Leaf)) { throw "Package file missing: $required. Extract the release zip before running this installer." }
}
if ($packagePath.TrimEnd('\', '/') -eq $serverPath.TrimEnd('\', '/')) { throw 'Extract the release into a separate directory before installation.' }
if (-not $InstallModLoader) {
    foreach ($required in @('modlauncher.jar', 'javassist.jar')) {
        if (-not (Test-Path -LiteralPath (Join-Path $serverPath $required) -PathType Leaf)) { throw "Existing server modloader not found ($required). Use -InstallModLoader to download the pinned official release." }
    }
}
Assert-ServerStopped
if (-not $PSCmdlet.ShouldProcess($serverPath, 'Back up and install WurmRail server mod')) { return }

$temporaryPath = Join-Path ([IO.Path]::GetTempPath()) ('WurmRail-install-' + [Guid]::NewGuid().ToString('N'))
$backupPath = Assert-ContainedPath $serverPath (Join-Path $serverPath ('wurmrail-backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')))
$writeOperations = New-Object 'System.Collections.Generic.List[object]'
try {
    if ($InstallModLoader) {
        New-Item -ItemType Directory -Path $temporaryPath -Force | Out-Null
        $archivePath = Assert-ContainedPath $temporaryPath (Join-Path $temporaryPath 'modloader.zip')
        Write-Host 'Downloading official WurmServerModLauncher v0.47.'
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $loaderUrl -OutFile $archivePath -UseBasicParsing
        if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $loaderHash) { throw 'Modloader checksum mismatch; no server files were modified.' }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
        try {
            foreach ($filename in $loaderFiles) {
                $entry = $archive.GetEntry($filename)
                if ($null -eq $entry) { throw "Official modloader archive is missing $filename." }
                $stagedFile = Assert-ContainedPath $temporaryPath (Join-Path $temporaryPath $filename)
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $stagedFile, $false)
            }
        } finally { $archive.Dispose() }
    }
    # Recheck after a potentially slow download, before the first server write.
    Assert-ServerStopped
    $relativeFiles = @('mods/wurmrail/wurmrail.jar')
    $sourceOverrides = @{}
    $configPath = Join-Path $serverPath 'mods/wurmrail.properties'
    if ($ReplaceConfig -or -not (Test-Path -LiteralPath $configPath)) {
        $relativeFiles += 'mods/wurmrail.properties'
    } else {
        # ISO-8859-1 maps every byte one-to-one. Change only the old numeric default;
        # do not normalize line endings, a BOM, non-ASCII bytes or unrelated settings.
        $configEncoding = [Text.Encoding]::GetEncoding(28591)
        $existingConfig = $configEncoding.GetString([IO.File]::ReadAllBytes($configPath))
        $updatedConfig = Update-WurmRailDefaultSpeed $existingConfig
        if ($updatedConfig -cne $existingConfig) {
            New-Item -ItemType Directory -Path $temporaryPath -Force | Out-Null
            $stagedConfig = Assert-ContainedPath $temporaryPath (Join-Path $temporaryPath 'wurmrail.properties')
            [IO.File]::WriteAllBytes($stagedConfig, $configEncoding.GetBytes($updatedConfig))
            $sourceOverrides['mods/wurmrail.properties'] = $stagedConfig
            $relativeFiles += 'mods/wurmrail.properties'
            Write-Host 'Updating the old default maxSpeed from 3 to 5.0; preserving all other configuration.'
        } else { Write-Host 'Keeping existing mods/wurmrail.properties and custom speed cap. Use -ReplaceConfig to replace it with defaults.' }
    }
    if ($InstallModLoader) { $relativeFiles += $loaderFiles }

    New-Item -ItemType Directory -Path $backupPath -Force | Out-Null
    # Complete every backup before replacing any existing server file.
    foreach ($relativePath in $relativeFiles) {
        $target = Assert-ContainedPath $serverPath (Join-Path $serverPath $relativePath)
        if (Test-Path -LiteralPath $target -PathType Container) { throw "Expected a file but found a directory: $target" }
        $existed = Test-Path -LiteralPath $target -PathType Leaf
        $savedFile = Assert-ContainedPath $backupPath (Join-Path $backupPath $relativePath)
        if ($existed) {
            New-Item -ItemType Directory -Path (Split-Path $savedFile -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $target -Destination $savedFile
        }
        $sourceRoot = if ($loaderFiles -contains $relativePath) { $temporaryPath } else { $packagePath }
        $source = if ($sourceOverrides.ContainsKey($relativePath)) { $sourceOverrides[$relativePath] } else { Join-Path $sourceRoot $relativePath }
        $writeOperations.Add([pscustomobject]@{ RelativePath = $relativePath; Source = $source; Target = $target; Backup = $savedFile; Existed = $existed; Written = $false })
    }
    $writeOperations | Select-Object RelativePath, Existed | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backupPath 'manifest.json') -Encoding UTF8
    foreach ($operation in $writeOperations) {
        New-Item -ItemType Directory -Path (Split-Path $operation.Target -Parent) -Force | Out-Null
        # Mark first so a partially failed copy is restored by the catch block.
        $operation.Written = $true
        Copy-Item -LiteralPath $operation.Source -Destination $operation.Target -Force
    }
    Write-Host "Installed WurmRail in $serverPath"
    Write-Host "Backup: $backupPath"
    Write-Host 'Start the server with modlauncher.bat. Read QUICKSTART.md for setup and README.md for controls.'
    Write-Host 'Install the matching WurmRail client update on every player PC for bridge track rendering. Read QUICKSTART.md.'
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
            [IO.Path]::GetFileName($resolvedTemp).StartsWith('WurmRail-install-')) {
            Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
        }
    }
}
