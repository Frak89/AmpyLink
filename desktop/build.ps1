param([Parameter(Mandatory=$true)][string]$RuntimePath)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$out = Join-Path $PSScriptRoot 'build/AmpyLink-Fusion'
$classes = Join-Path $PSScriptRoot 'build/classes'
New-Item -Force -ItemType Directory $classes, "$out/lib" | Out-Null
if (!(Test-Path "$RuntimePath/bin/javaw.exe")) { throw 'Select a Windows x64 Java 17 runtime' }
javac --release 17 -encoding UTF-8 -d $classes "$root/app/src/main/java/io/ampy/link/Link.java" "$PSScriptRoot/src/io/ampy/link/Desktop.java"
if ($LASTEXITCODE) { throw 'javac failed' }
jar --create --file "$out/lib/orb.jar" --main-class io.ampy.link.Desktop -C $classes .
if ($LASTEXITCODE) { throw 'jar failed' }
$env:GOARCH = 'amd64'
$env:GOOS = 'windows'
go build -trimpath -ldflags '-s -w -H windowsgui' -o "$out/AmpyLink-Fusion.exe" "$PSScriptRoot/launcher.go"
if ($LASTEXITCODE) { throw 'Go build failed' }
New-Item -Force -ItemType Directory "$out/runtime" | Out-Null
Copy-Item "$RuntimePath/*" "$out/runtime/" -Recurse -Force
Compress-Archive -Force -Path $out -DestinationPath "$PSScriptRoot/build/AmpyLink-Fusion-Windows-x64.zip"
