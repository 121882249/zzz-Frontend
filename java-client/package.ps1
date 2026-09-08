$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& ./build.ps1
Remove-Item -Recurse -Force dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force dist | Out-Null
$fxLib = (Get-ChildItem build -Directory -Filter "javafx-*-windows-x64" | Select-Object -First 1).FullName + "/lib"
New-Item -ItemType Directory -Force build/input | Out-Null
Copy-Item build/TokenPro.jar build/input/TokenPro.jar -Force
New-Item -ItemType Directory -Force build/input/fx | Out-Null
Copy-Item "$fxLib/*.dll" build/input/fx -Force
& "$env:JAVA_HOME/bin/jpackage.exe" --type exe --name TokenPro --app-version 1.1.3 `
  --input build/input --main-jar TokenPro.jar --main-class work.tokenpro.client.Main `
  --module-path "$env:JAVA_HOME/jmods;$fxLib" `
  --add-modules java.base,java.desktop,java.net.http,jdk.httpserver,javafx.controls,javafx.web,javafx.swing `
  --java-options '-Djava.library.path=$APPDIR/fx' `
  --icon ../Router.ico `
  --vendor TokenPro --description "TokenPro cross-platform desktop client" --dest dist `
  --win-menu --win-shortcut --win-dir-chooser
Write-Host "Created Windows installer in java-client/dist"
