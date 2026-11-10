$ErrorActionPreference = 'Stop'
$pilotoConfigJava = Join-Path $PSScriptRoot 'servicios\java\java-activo.json'
if (-not (Test-Path -LiteralPath $pilotoConfigJava)) { throw 'Falta el OpenJDK local del proyecto. Revisa LICENCIAS-ACTUAL.md.' }
$pilotoJdk = Get-Content -LiteralPath $pilotoConfigJava -Raw | ConvertFrom-Json
$pilotoJavaHome = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $pilotoJdk.ruta_relativa))
$pilotoServicioJava = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'servicios\java')) + [System.IO.Path]::DirectorySeparatorChar
if (-not $pilotoJavaHome.StartsWith($pilotoServicioJava, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'El JDK configurado debe estar dentro de servicios\java.' }
$pilotoJavaExe = Join-Path $pilotoJavaHome 'bin\java.exe'
$pilotoJavacExe = Join-Path $pilotoJavaHome 'bin\javac.exe'
foreach ($pilotoArchivo in @(@{ruta=$pilotoJavaExe; hash=$pilotoJdk.java_exe_sha256}, @{ruta=$pilotoJavacExe; hash=$pilotoJdk.javac_exe_sha256})) {
    if (-not (Test-Path -LiteralPath $pilotoArchivo.ruta) -or (Get-FileHash -LiteralPath $pilotoArchivo.ruta -Algorithm SHA256).Hash.ToLowerInvariant() -ne $pilotoArchivo.hash) { throw 'El JDK local no coincide con los ejecutables verificados.' }
}
$pilotoReleaseJava = Get-Content -LiteralPath (Join-Path $pilotoJavaHome 'release') -Raw
if ($pilotoReleaseJava -notmatch 'IMPLEMENTOR="Eclipse Adoptium"' -or $pilotoReleaseJava -notmatch 'JAVA_VERSION="21\.') { throw 'Se requiere Eclipse Temurin OpenJDK 21.' }
[pscustomobject]@{Home=$pilotoJavaHome; Java=$pilotoJavaExe; Javac=$pilotoJavacExe; Version=$pilotoJdk.version}
