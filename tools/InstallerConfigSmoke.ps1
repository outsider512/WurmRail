[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$installerPath = Join-Path $PSScriptRoot '../Install-WurmRail.ps1'
$parseTokens = $null
$parseErrors = $null
$installerAst = [System.Management.Automation.Language.Parser]::ParseFile(
    [IO.Path]::GetFullPath($installerPath), [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw 'Installer PowerShell syntax is invalid.' }
$definitions = @($installerAst.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Update-WurmRailDefaultSpeed'
}, $true))
if ($definitions.Count -ne 1) { throw 'Expected one pure configuration migration function.' }
# Extract only the function definition. Never execute installer setup, process checks,
# downloads, file writes, backups, ShouldProcess or installation code in this test.
Invoke-Expression $definitions[0].Extent.Text
$script:assertions = 0
function Assert-Config([string]$InputText, [string]$Expected, [string]$Reason) {
    $actual = Update-WurmRailDefaultSpeed $InputText
    $script:assertions++
    if ($actual -cne $Expected) { throw "Installer configuration migration failed: $Reason" }
}

Assert-Config 'maxSpeed=3' 'maxSpeed=5.0' 'old integer default upgrades'
Assert-Config 'maxSpeed=3.0' 'maxSpeed=5.0' 'old decimal default upgrades'
Assert-Config "maxSpeed : `t3.000 `t" "maxSpeed : `t5.0 `t" 'colon separator and surrounding whitespace survive'
Assert-Config "`tmaxSpeed `t 3`f" "`tmaxSpeed `t 5.0`f" 'Java properties whitespace separator and form feed survive'
Assert-Config 'maxSpeed=+3e0' 'maxSpeed=5.0' 'equivalent numeric three upgrades'
Assert-Config 'maxSpeed=3D' 'maxSpeed=5.0' 'Java floating suffix is accepted'
foreach ($value in @('1', '0.25', '2.5', '3.1', '5', '5.0', '6', 'NaN', 'Infinity', '', 'not-a-speed')) {
    Assert-Config ("maxSpeed=$value") ("maxSpeed=$value") "custom or invalid value $value is preserved"
}
Assert-Config "# maxSpeed=3`r`n! maxSpeed:3`r`nmaxSpeed=5`r`n" "# maxSpeed=3`r`n! maxSpeed:3`r`nmaxSpeed=5`r`n" 'comments never override the effective custom cap'
Assert-Config "# keep me`r`noperators=Alice,Bob`r`nmaxSpeed=3.0`r`nmaxCars=8`r`n" "# keep me`r`noperators=Alice,Bob`r`nmaxSpeed=5.0`r`nmaxCars=8`r`n" 'CRLF, comments, settings and final newline survive'
Assert-Config "maxSpeed=3`nmaxSpeed=1`n" "maxSpeed=3`nmaxSpeed=1`n" 'last duplicate custom cap prevents an earlier default rewrite'
Assert-Config "maxSpeed=1`nmaxSpeed=3`n" "maxSpeed=1`nmaxSpeed=5.0`n" 'only the final effective default changes'
Assert-Config "maxSpeed=3.0`nmaxSpeed=3.0" "maxSpeed=3.0`nmaxSpeed=5.0" 'previous duplicate bytes are preserved'
Assert-Config "maxSpeed=3`nmax\u0053peed=1" "maxSpeed=3`nmax\u0053peed=1" 'escaped effective key prevents changing a shadowed literal key'
Assert-Config "maxSpeed=3`nmax\Speed=5" "maxSpeed=3`nmax\Speed=5" 'Java ordinary key escapes also participate in duplicate precedence'
Assert-Config 'max\u0053peed=3' 'max\u0053peed=3' 'only literal maxSpeed defaults are rewritten'
Assert-Config 'maxSpeed=\u0033' 'maxSpeed=\u0033' 'escaped numeric values are preserved conservatively'
Assert-Config "maxSpeedLimit=3`nMaxSpeed=3`noperators=Alice" "maxSpeedLimit=3`nMaxSpeed=3`noperators=Alice" 'similar or differently cased keys are not defaults'
Assert-Config '' '' 'empty configuration is unchanged'
Assert-Config "maxSpeed=3 # custom note`n" "maxSpeed=3 # custom note`n" 'inline text is part of a Java property value rather than a comment'
Assert-Config "description=other\`r`n maxSpeed=3`r`nmaxSpeed=5" "description=other\`r`n maxSpeed=3`r`nmaxSpeed=5" 'continuation contents never become a separate property'
Assert-Config "maxSpeed=\`r`n 3.0`r`n" "maxSpeed=\`r`n 5.0`r`n" 'continued default value upgrades without rewriting line structure'
Assert-Config "maxSpeed=3\`n .0`n" "maxSpeed=5.0\`n `n" 'split numeric token upgrades while preserving the backslash and newline bytes'
Assert-Config "# comment\`nmaxSpeed=3" "# comment\`nmaxSpeed=5.0" 'backslash in a comment does not join the next line'
Assert-Config "maxSpeed=3`rmaxSpeed=5`r" "maxSpeed=3`rmaxSpeed=5`r" 'lone carriage-return lines retain final-value precedence'
$unicodeBom = [string][char]0xfeff
Assert-Config ($unicodeBom + 'maxSpeed=3.0') ($unicodeBom + 'maxSpeed=5.0') 'a decoded BOM remains unchanged'

$encoding = [Text.Encoding]::GetEncoding(28591)
$rawPrefix = [byte[]]@(0xef, 0xbb, 0xbf)
$rawBody = $encoding.GetBytes("builders=Jos$([char]0xe9),$([char]0xff)`r`nmaxSpeed : 3.0 `t`r`nopaque=\u00e9\u263a`n")
$rawInput = [byte[]]($rawPrefix + $rawBody)
$rawExpected = [byte[]]($rawPrefix + $encoding.GetBytes("builders=Jos$([char]0xe9),$([char]0xff)`r`nmaxSpeed : 5.0 `t`r`nopaque=\u00e9\u263a`n"))
$rawActual = $encoding.GetBytes((Update-WurmRailDefaultSpeed ($encoding.GetString($rawInput))))
$script:assertions++
if ($rawActual.Length -ne $rawExpected.Length) { throw 'Installer migration changed byte length outside the numeric token.' }
for ($index = 0; $index -lt $rawExpected.Length; $index++) {
    if ($rawActual[$index] -ne $rawExpected[$index]) { throw "Installer migration failed to preserve byte $index." }
}
$rawBomOnly = $encoding.GetString([byte[]]($rawPrefix + $encoding.GetBytes('maxSpeed=3')))
$expectedBomOnly = $encoding.GetString([byte[]]($rawPrefix + $encoding.GetBytes('maxSpeed=5.0')))
Assert-Config $rawBomOnly $expectedBomOnly 'UTF-8 BOM bytes remain ahead of an upgraded first property'
Write-Host "PASS pure installer default-speed migration: $script:assertions assertions; no installation executed."
