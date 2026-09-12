param([Parameter(Mandatory=$true)][string]$Bootstrap,[Parameter(Mandatory=$true)][string]$Image,[Parameter(Mandatory=$true)][string]$Delta,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
if($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted'){throw 'Native bootstrap acceptance is restricted to disposable hosted runners'}
$workspace=[IO.Path]::GetFullPath($env:GITHUB_WORKSPACE).TrimEnd('\')+'\'
foreach($item in @($Bootstrap,$Image,$Delta,$Output)) {
    $absolute=[IO.Path]::GetFullPath($item)
    if(-not $absolute.StartsWith($workspace,[StringComparison]::OrdinalIgnoreCase)){throw 'Fixture outside workspace'}
}
if(Test-Path -LiteralPath $Output){throw 'Fixture must be new'}
[void][IO.Directory]::CreateDirectory($Output)
$app=Join-Path $Output 'Existing Custom Location/TokenPro'
[void][IO.Directory]::CreateDirectory((Split-Path -Parent $app))
Copy-Item -LiteralPath $Image -Destination $app -Recurse
$core=Join-Path $app 'app/TokenPro.jar'
# Simulate the old version constant in a full native image. Original image is untouched.
$archive=[IO.Compression.ZipFile]::OpenRead($core)
$fixtureCore=$core+'.fixture'
$fixtureArchive=[IO.Compression.ZipFile]::Open($fixtureCore,[IO.Compression.ZipArchiveMode]::Create)
try {
    $entry=$archive.GetEntry('work/tokenpro/client/Main.class')
    $memory=[IO.MemoryStream]::new(); $input=$entry.Open()
    try {$input.CopyTo($memory)} finally {$input.Dispose()}
    $bytes=$memory.ToArray(); $memory.Dispose()
    $needle=[Text.Encoding]::ASCII.GetBytes('1.2.65'); $changes=0
    for($i=0;$i -le $bytes.Length-$needle.Length;$i++) {
        $matches=$true
        for($j=0;$j -lt $needle.Length;$j++){if($bytes[$i+$j] -ne $needle[$j]){$matches=$false;break}}
        if($matches){$bytes[$i+$needle.Length-1]=[byte][char]'4';$changes++}
    }
    if($changes -ne 1){throw 'Unexpected version constant count'}
    foreach($original in $archive.Entries) {
        $replacement=$fixtureArchive.CreateEntry($original.FullName).Open()
        try {
            if($original.FullName -eq 'work/tokenpro/client/Main.class') {
                $replacement.Write($bytes,0,$bytes.Length)
            } else {
                $originalStream=$original.Open()
                try {$originalStream.CopyTo($replacement)} finally {$originalStream.Dispose()}
            }
        } finally {$replacement.Dispose()}
    }
} finally {$fixtureArchive.Dispose(); $archive.Dispose()}
# Only replace the disposable copied core; never mutate the packaged image.
[IO.File]::Move($fixtureCore,$core,$true)
$launcher=Join-Path $app 'TokenPro.exe'
$hash=(Get-FileHash -LiteralPath $core -Algorithm SHA256).Hash.ToLowerInvariant()
$deltaHash=(Get-FileHash -LiteralPath $Delta -Algorithm SHA256).Hash.ToLowerInvariant()
$encodedPath=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($launcher))
$encodedHash=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($deltaHash))
$worker=Start-Process -FilePath $Bootstrap -ArgumentList @('--apply',$encodedPath,$hash,$encodedHash) -WindowStyle Hidden -PassThru -Wait `
    -RedirectStandardError (Join-Path $Output 'apply-error.log')
if($worker.ExitCode -ne 0){Get-Content -LiteralPath (Join-Path $Output 'apply-error.log'); throw "Native bootstrap apply failed: $($worker.ExitCode)"}
$native=Start-Process -FilePath $launcher -ArgumentList '--self-test' -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $Output 'native.log') -RedirectStandardError (Join-Path $Output 'native-error.log')
if(-not $native.WaitForExit(120000)){ $native.Kill($true); throw 'Native bootstrap fixture timed out' }
$native.WaitForExit()
if($native.ExitCode -ne 0){Get-Content -LiteralPath (Join-Path $Output 'native-error.log') -Tail 20; throw 'Native image failed self-test after bootstrap'}
$result=[IO.File]::ReadAllText((Join-Path $Output 'native.log'))
if($result -notmatch '(\d+) checks passed'){throw 'Native test completion missing'}
Write-Output "Native bootstrap acceptance: $($Matches[0]); original installation location preserved. UAC interaction not tested."
