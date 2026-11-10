param([string]$SalidaClases)
$ErrorActionPreference = 'Stop'
$pilotoRuntime = & (Join-Path $PSScriptRoot 'Resolver-JavaLocal.ps1')
$pilotoDependencias = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'classpath.txt') -Raw).Trim()
foreach ($pilotoJar in $pilotoDependencias.Split(';')) { if (-not (Test-Path -LiteralPath $pilotoJar)) { throw "Falta la dependencia local: $pilotoJar" } }
$pilotoFuentes = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java\mx\edu\piloto') -Filter '*.java' | ForEach-Object FullName)
if ($pilotoFuentes.Count -eq 0) { throw 'No se encontraron las fuentes Java.' }
$pilotoClases = if ([string]::IsNullOrWhiteSpace($SalidaClases)) { Join-Path $PSScriptRoot 'target\classes' } else { $SalidaClases }
New-Item -ItemType Directory -Force -Path $pilotoClases | Out-Null
& $pilotoRuntime.Javac -encoding UTF-8 --release 21 -cp $pilotoDependencias -d $pilotoClases @pilotoFuentes
if ($LASTEXITCODE -ne 0) { throw 'Fallo la compilacion. Revisa el mensaje anterior.' }
Write-Host "Compiladas $($pilotoFuentes.Count) clases fuente con $($pilotoRuntime.Version), usando dependencias locales."
