param([int]$Puerto = 8765)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'qdrant-activo.json')) { & (Join-Path $PSScriptRoot 'Iniciar-Qdrant.ps1') -SegundoPlano }
$pilotoRuntime = & (Join-Path $PSScriptRoot 'Resolver-JavaLocal.ps1')
$pilotoJava = $pilotoRuntime.Java
$pilotoDependencias = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'classpath.txt') -Raw).Trim()
$pilotoClases = Join-Path $PSScriptRoot 'target\classes'
Write-Host "Abre http://127.0.0.1:$Puerto/ en tu navegador. Deja esta ventana abierta; Ctrl+C detiene el servidor."
& $pilotoJava -Xmx256m -cp ($pilotoClases + ';' + $pilotoDependencias) mx.edu.piloto.InterfazLocal $PSScriptRoot $Puerto
if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar la interfaz. Revisa el mensaje anterior.' }
