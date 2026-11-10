param([switch]$SegundoPlano)
$ErrorActionPreference = 'Stop'
$pilotoServicio = Join-Path $PSScriptRoot 'servicios\qdrant'
$pilotoDistribucion = Get-Content -LiteralPath (Join-Path $pilotoServicio 'distribucion.json') -Raw | ConvertFrom-Json
$pilotoExe = $pilotoDistribucion.ejecutable
$pilotoConfig = Join-Path $pilotoServicio 'config-local.yaml'
if ((Get-FileHash -LiteralPath $pilotoExe -Algorithm SHA256).Hash.ToLowerInvariant() -ne $pilotoDistribucion.ejecutable_sha256) { throw 'El ejecutable no coincide con la copia verificada.' }
$pilotoRespuesta = $null
try { $pilotoRespuesta = Invoke-RestMethod -Uri 'http://127.0.0.1:6333/' -TimeoutSec 2 } catch { }
if ($null -ne $pilotoRespuesta) {
    if ($pilotoRespuesta.version -ne '1.20.0') { throw 'El puerto 6333 responde con otra version.' }
    Write-Host 'Qdrant 1.20.0 ya esta activo.'
    return
}
if ($SegundoPlano) {
    $pilotoProceso = Start-Process -FilePath $pilotoExe -ArgumentList @('--config-path', ('"' + $pilotoConfig + '"'), '--disable-telemetry') -WorkingDirectory $pilotoServicio -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $pilotoServicio 'servidor.log') -RedirectStandardError (Join-Path $pilotoServicio 'servidor-error.log')
    for ($pilotoIntento = 0; $pilotoIntento -lt 60; $pilotoIntento++) {
        $pilotoProceso.Refresh()
        if ($pilotoProceso.HasExited) { throw 'Qdrant termino. Revisa los registros en servicios\qdrant.' }
        try {
            $pilotoRespuesta = Invoke-RestMethod -Uri 'http://127.0.0.1:6333/' -TimeoutSec 1
            if ($pilotoRespuesta.version -eq '1.20.0') { Write-Host 'Qdrant local iniciado.'; return }
        } catch { }
        Start-Sleep -Milliseconds 300
    }
    throw 'Qdrant no respondio a tiempo. Revisa sus registros antes de reiniciarlo.'
}
Write-Host 'Qdrant local en 127.0.0.1:6333. Ctrl+C para detener.'
Push-Location -LiteralPath $pilotoServicio
try { & $pilotoExe --config-path $pilotoConfig --disable-telemetry; if ($LASTEXITCODE -ne 0) { throw 'Qdrant termino con error.' } }
finally { Pop-Location }
