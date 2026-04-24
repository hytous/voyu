$ErrorActionPreference = 'Stop'

$infraDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$composeFile = Join-Path $infraDir 'docker-compose.middleware.yml'

docker compose -f $composeFile down
