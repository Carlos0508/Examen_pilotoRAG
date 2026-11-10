$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Iniciar-Qdrant.ps1') -SegundoPlano
$pilotoRuntime = & (Join-Path $PSScriptRoot 'Resolver-JavaLocal.ps1')
$pilotoJava = $pilotoRuntime.Java
$pilotoCp = (Join-Path $PSScriptRoot 'target\classes') + ';' + (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'classpath.txt') -Raw).Trim()
& $pilotoJava -Xmx512m -cp $pilotoCp mx.edu.piloto.QdrantLocal activar $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'No se activo Qdrant. El corpus documental anterior se conserva.' }
Write-Host 'QDRANT_LOCAL_ACTIVADO - Puedes iniciar la interfaz.'
