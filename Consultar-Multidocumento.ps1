param([string]$Pregunta)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'qdrant-activo.json')) { & (Join-Path $PSScriptRoot 'Iniciar-Qdrant.ps1') -SegundoPlano }
$pilotoRuntime = & (Join-Path $PSScriptRoot 'Resolver-JavaLocal.ps1')
$pilotoJava = $pilotoRuntime.Java
if (-not (Test-Path -LiteralPath $pilotoJava)) { throw 'No se encontro Java 21 en la ruta configurada. Revisa MULTIDOCUMENTO.md.' }
$pilotoClases = Join-Path $PSScriptRoot 'target\classes'
$pilotoDependencias = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'classpath.txt') -Raw).Trim()
$pilotoIndice = & $pilotoJava -cp ($pilotoClases + ';' + $pilotoDependencias) mx.edu.piloto.RegistroAcervo $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'No se pudo resolver el indice activo.' }
if ([string]::IsNullOrWhiteSpace($Pregunta)) { $Pregunta = Read-Host 'Escribe tu pregunta; puedes indicar la clave de la circular' }
if ([string]::IsNullOrWhiteSpace($Pregunta)) { throw 'Escribe una pregunta.' }
$pilotoDestino = Join-Path $PSScriptRoot ('consulta-multi-' + [guid]::NewGuid().ToString('N'))
$pilotoTrabajo = Join-Path $PSScriptRoot 'work-interfaz'
New-Item -ItemType Directory -Force -Path $pilotoTrabajo | Out-Null
$pilotoPreguntaArchivo = Join-Path $pilotoTrabajo ('pregunta-cli-' + [guid]::NewGuid().ToString('N') + '.txt')
[System.IO.File]::WriteAllText($pilotoPreguntaArchivo, $Pregunta, (New-Object System.Text.UTF8Encoding($false)))
& $pilotoJava -Xmx512m -cp ($pilotoClases + ';' + $pilotoDependencias) mx.edu.piloto.ConsultaDesdeArchivo $pilotoIndice $pilotoPreguntaArchivo $pilotoDestino
if ($LASTEXITCODE -ne 0) { throw "La consulta fallo. Revisa Ollama y el mensaje anterior. Salida: $pilotoDestino" }
