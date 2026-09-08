$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME) { throw "JDK 21 is required. Set JAVA_HOME first." }
Remove-Item -Recurse -Force build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force build/classes | Out-Null
$sources = Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object FullName
& "$env:JAVA_HOME/bin/javac.exe" --release 21 --add-modules jdk.httpserver -encoding UTF-8 -d build/classes $sources
New-Item -ItemType Directory -Force build/classes/assets | Out-Null
Copy-Item ../Resources/TokenProCosmosIcon.png build/classes/assets/TokenProCosmosIcon.png
Copy-Item ../Resources/LoginCosmos-v2.png build/classes/assets/LoginCosmos-v2.png
Copy-Item ../Resources/ModelUniverseVortex.png build/classes/assets/ModelUniverseVortex.png
Copy-Item ../Resources/OpenAIBlossomRuntime.png build/classes/assets/OpenAIBlossomRuntime.png
Copy-Item ../Resources/ClaudeSparkRuntime.png build/classes/assets/ClaudeSparkRuntime.png
Copy-Item ../Resources/GeminiSparkTransparent.png build/classes/assets/GeminiSparkTransparent.png
Copy-Item ../Resources/GrokMarkTransparent.png build/classes/assets/GrokMarkTransparent.png
Copy-Item ../Resources/UnknownModelRuntime.png build/classes/assets/UnknownModelRuntime.png
@"
Main-Class: work.tokenpro.client.Main
Implementation-Title: TokenPro
Implementation-Version: 1.1.1

"@ | Set-Content -Encoding ascii build/manifest.mf
& "$env:JAVA_HOME/bin/jar.exe" --create --file build/TokenPro.jar --manifest build/manifest.mf -C build/classes .
& "$env:JAVA_HOME/bin/java.exe" -jar build/TokenPro.jar --self-test
Write-Host "Built java-client/build/TokenPro.jar"
