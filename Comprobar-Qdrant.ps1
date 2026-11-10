$ErrorActionPreference = 'Stop'
$pilotoRuntime = & (Join-Path $PSScriptRoot 'Resolver-JavaLocal.ps1')
$pilotoJava = $pilotoRuntime.Java
$pilotoCp = (Join-Path $PSScriptRoot 'target\classes') + ';' + (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'classpath.txt') -Raw).Trim()
& $pilotoJava -Xmx512m -cp $pilotoCp mx.edu.piloto.QdrantLocal verificar $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Qdrant no coincide con el indice activo.' }
