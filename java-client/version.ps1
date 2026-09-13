$ErrorActionPreference = 'Stop'
$source = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'src/main/java/work/tokenpro/client/Main.java')
$versionMatches = [regex]::Matches($source, 'public static final String VERSION = "([0-9]+\.[0-9]+\.[0-9]+)";')
if ($versionMatches.Count -ne 1) { throw 'Invalid Main.VERSION' }
$releaseVersion = $versionMatches[0].Groups[1].Value
if ($env:GITHUB_REF_TYPE -eq 'tag' -and $env:GITHUB_REF_NAME -ne "v$releaseVersion") {
    throw 'Release tag does not match Main.VERSION'
}
Write-Output $releaseVersion
