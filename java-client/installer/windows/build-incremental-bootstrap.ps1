param([Parameter(Mandatory=$true)][string]$DeltaPath, [Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$clientRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$outputRoot=[IO.Path]::GetFullPath($OutputDirectory)
if(-not ($outputRoot.StartsWith($clientRoot+'\build\',[StringComparison]::OrdinalIgnoreCase) -or $outputRoot -eq $clientRoot+'\dist')){throw 'Bootstrap output must be a build artifact directory'}
$delta=(Resolve-Path -LiteralPath $DeltaPath).Path
if((Get-Item -LiteralPath $delta).Length -lt 50000){throw 'Delta artifact is incomplete'}
$compiler=Join-Path $env:SystemRoot 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if(-not (Test-Path -LiteralPath $compiler)){throw 'The preinstalled .NET Framework compiler is required; no tools will be downloaded'}
[void][IO.Directory]::CreateDirectory($outputRoot)
$exe=Join-Path $outputRoot 'TokenPro-1.2.65-Windows-incremental.exe'
& $compiler /nologo /target:winexe /platform:x64 /codepage:65001 `
    '/reference:System.Windows.Forms.dll' '/reference:System.Drawing.dll' '/reference:System.IO.Compression.dll' '/reference:System.IO.Compression.FileSystem.dll' '/reference:System.Management.dll' '/reference:System.Web.Extensions.dll' `
    ("/win32manifest:"+(Join-Path $PSScriptRoot 'bootstrap.manifest')) `
    ("/win32icon:"+(Join-Path $clientRoot '../Router.ico')) `
    ("/resource:"+$delta+',TokenPro.delta') `
    ("/resource:"+(Join-Path $PSScriptRoot 'cosmos-installer-v1.png')+',TokenPro.background') `
    ("/resource:"+(Join-Path $clientRoot '../Router.ico')+',TokenPro.icon') `
    ("/out:"+$exe) (Join-Path $PSScriptRoot 'IncrementalBootstrap.cs')
if($LASTEXITCODE -ne 0){throw 'Incremental bootstrap compilation failed'}
$fixture=Join-Path $clientRoot ('build/bootstrap-test-'+[Guid]::NewGuid().ToString('N'))
$process=Start-Process -FilePath $exe -ArgumentList @('--self-test',('"'+$fixture+'"')) -WindowStyle Hidden -PassThru -Wait `
    -RedirectStandardOutput (Join-Path $outputRoot 'bootstrap-self-test.log') -RedirectStandardError (Join-Path $outputRoot 'bootstrap-self-test-error.log')
if($process.ExitCode -ne 0){Get-Content -LiteralPath (Join-Path $outputRoot 'bootstrap-self-test-error.log') -Tail 20; throw 'Incremental bootstrap self-test failed'}
Write-Output "Built standalone delta-only update entry: $exe"
