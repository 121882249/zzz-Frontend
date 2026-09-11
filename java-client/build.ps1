$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME) { throw "JDK 21 is required. Set JAVA_HOME first." }
Remove-Item -Recurse -Force build/classes -ErrorAction SilentlyContinue
Remove-Item -Force build/TokenPro.jar -ErrorAction SilentlyContinue
Remove-Item -Force build/TokenPro-update.jar -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force build/classes | Out-Null
$sources = Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object FullName
& "$env:JAVA_HOME/bin/javac.exe" --release 21 --add-modules jdk.httpserver -encoding UTF-8 -d build/classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
New-Item -ItemType Directory -Force build/classes/assets | Out-Null
Copy-Item ../Resources/TokenProCosmosIcon.png build/classes/assets/TokenProCosmosIcon.png
Copy-Item ../Resources/LoginCosmos-v2.png build/classes/assets/LoginCosmos-v2.png
Copy-Item ../Resources/ModelUniverseVortex.png build/classes/assets/ModelUniverseVortex.png
Copy-Item ../Resources/OpenAIBlossomRuntime.png build/classes/assets/OpenAIBlossomRuntime.png
Copy-Item ../Resources/ClaudeSparkRuntime.png build/classes/assets/ClaudeSparkRuntime.png
Copy-Item ../Resources/GeminiSparkTransparent.png build/classes/assets/GeminiSparkTransparent.png
Copy-Item ../Resources/GrokMarkTransparent.png build/classes/assets/GrokMarkTransparent.png
Copy-Item ../Resources/UnknownModelRuntime.png build/classes/assets/UnknownModelRuntime.png
Copy-Item ../Resources/CodexOriginal.png build/classes/assets/CodexOriginal.png
Copy-Item ../Resources/ClaudeOriginal.png build/classes/assets/ClaudeOriginal.png
Copy-Item ../Resources/SparklesLucide.png build/classes/assets/SparklesLucide.png
Copy-Item ../Resources/CircleUserLucide.png build/classes/assets/CircleUserLucide.png
Copy-Item ../Resources/CircleUserPurple.png build/classes/assets/CircleUserPurple.png
Copy-Item ../Resources/WebCog.png build/classes/assets/WebCog.png
Copy-Item ../Resources/WebBook.png build/classes/assets/WebBook.png
Copy-Item ../Resources/RefreshCwLucide.png build/classes/assets/RefreshCwLucide.png
Copy-Item ../Resources/PlusLucide.png build/classes/assets/PlusLucide.png
@"
Main-Class: work.tokenpro.client.Main
Implementation-Title: TokenPro
Implementation-Version: 1.2.60

"@ | Set-Content -Encoding ascii build/manifest.mf
& "$env:JAVA_HOME/bin/jar.exe" --create --file build/TokenPro.jar --manifest build/manifest.mf -C build/classes .
if ($LASTEXITCODE -ne 0) { throw "TokenPro.jar packaging failed with exit code $LASTEXITCODE" }
& "$env:JAVA_HOME/bin/jar.exe" --create --file build/TokenPro-update.jar --no-manifest -C build/classes work
if ($LASTEXITCODE -ne 0) { throw "TokenPro-update.jar packaging failed with exit code $LASTEXITCODE" }
& "$env:JAVA_HOME/bin/java.exe" -jar build/TokenPro.jar --self-test
if ($LASTEXITCODE -ne 0) { throw "self-test failed with exit code $LASTEXITCODE" }
Write-Host "Built java-client/build/TokenPro.jar and TokenPro-update.jar"
