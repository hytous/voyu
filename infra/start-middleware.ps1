$ErrorActionPreference = 'Stop'

$infraDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$composeFile = Join-Path $infraDir 'docker-compose.middleware.yml'
$dataRoot = Join-Path $infraDir 'data'
$dockerDesktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'

$requiredDirs = @(
    (Join-Path $dataRoot 'mongodb'),
    (Join-Path $dataRoot 'elasticsearch'),
    (Join-Path $dataRoot 'kafka'),
    (Join-Path $dataRoot 'milvus'),
    (Join-Path $dataRoot 'milvus\etcd'),
    (Join-Path $dataRoot 'milvus\minio'),
    (Join-Path $dataRoot 'milvus\data')
)

foreach ($dir in $requiredDirs) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
}

function Test-DockerReady {
    docker ps *> $null
    return ($LASTEXITCODE -eq 0)
}

if (-not (Test-DockerReady)) {
    if (-not (Test-Path $dockerDesktop)) {
        throw "Docker Desktop not found at $dockerDesktop"
    }

    Write-Host 'Starting Docker Desktop...'
    Start-Process -FilePath $dockerDesktop | Out-Null

    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 4
        if (Test-DockerReady) {
            $ready = $true
            break
        }
    }

    if (-not $ready) {
        throw 'Docker daemon did not become ready in time.'
    }
}

Write-Host 'Starting Voyu middleware stack...'
docker compose -f $composeFile up -d
docker compose -f $composeFile ps
