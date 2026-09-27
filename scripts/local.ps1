param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Init', 'Up', 'Down', 'Status', 'Backend', 'BackendUp', 'BackendDown', 'Frontend', 'FrontendUp', 'FrontendDown', 'Restart', 'Logs')]
    [string]$Action,
    [ValidateSet('frontend', 'backend', 'mysql')]
    [string]$Service = 'backend'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot '.env'
$backendEnvFile = Join-Path $repoRoot '.env.backend'

function New-LocalPassword {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Import-DatabaseEnvironment {
    if (-not (Test-Path -LiteralPath $envFile)) {
        throw 'Local database settings are missing. Run scripts/local.ps1 Init first.'
    }
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*(#.*)?$') { continue }
        # Parse data only; never execute a dotenv file as PowerShell code.
        if ($line -notmatch '^(DB_NAME|DB_USERNAME|DB_PORT|DB_PASSWORD|MYSQL_ROOT_PASSWORD)=([A-Za-z0-9_]+)$') {
            throw 'Invalid .env entry. Use only the five settings in .env.example with unquoted alphanumeric/underscore values.'
        }
        $settings[$Matches[1]] = $Matches[2]
    }
    foreach ($name in 'DB_NAME', 'DB_USERNAME', 'DB_PORT', 'DB_PASSWORD', 'MYSQL_ROOT_PASSWORD') {
        if (-not $settings.ContainsKey($name) -or $settings[$name].StartsWith('replace_with_')) {
            throw "Configure $name in .env before starting."
        }
    }
    $port = 0
    if (-not [int]::TryParse($settings.DB_PORT, [ref]$port) -or $port -lt 1024 -or $port -gt 65535) {
        throw 'DB_PORT must be between 1024 and 65535.'
    }
    foreach ($name in $settings.Keys) {
        [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
    }
    # TLS is disabled only for this database published on the local loopback interface.
    $env:DB_URL = "jdbc:mysql://127.0.0.1:$port/$($settings.DB_NAME)?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
}

function Enable-DockerCli {
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin",
        "$env:ProgramFiles\Docker\Docker\resources\bin"
    )
    foreach ($directory in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $directory 'docker.exe')) {
            # Include the credential helper as well as docker.exe in this process's PATH.
            $env:PATH = "$directory;$env:PATH"
            break
        }
    }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker CLI was not found. Install/start Docker Desktop and enable Linux containers.'
    }
}

function Import-BackendEnvironment {
    if (-not (Test-Path -LiteralPath $backendEnvFile)) {
        throw 'Backend runtime settings are missing. Run scripts/local.ps1 Init first.'
    }
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath $backendEnvFile) {
        if ($line -match '^\s*(#.*)?$') { continue }
        if ($line -notmatch '^(AZURE_OPENAI_BASE_URL|AZURE_OPENAI_DEPLOYMENT|CHAT_API_KEY)=([^\s''"$`]+)$') {
            throw 'Invalid .env.backend entry. Use the three unquoted settings in backend.env.example.'
        }
        $settings[$Matches[1]] = $Matches[2]
    }
    foreach ($name in 'AZURE_OPENAI_BASE_URL', 'AZURE_OPENAI_DEPLOYMENT', 'CHAT_API_KEY') {
        if (-not $settings.ContainsKey($name) -or $settings[$name].StartsWith('replace_with_')) {
            throw "Configure $name in .env.backend before starting."
        }
        [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
    }
}

Push-Location $repoRoot
try {
    if ($Action -eq 'Init') {
        if (Test-Path -LiteralPath $envFile) {
            Write-Host 'Existing .env preserved. No passwords or settings were changed.'
        } else {
            $lines = @(
                '# Local Docker database credentials. Do not commit or share this file.'
                'DB_NAME=spring_ai'
                'DB_USERNAME=spring_ai_app'
                'DB_PORT=3307'
                "DB_PASSWORD=$(New-LocalPassword)"
                "MYSQL_ROOT_PASSWORD=$(New-LocalPassword)"
            )
            [IO.File]::WriteAllLines($envFile, $lines, (New-Object Text.UTF8Encoding($false)))
            Write-Host 'Created ignored .env with separate random database passwords.'
        }
        if (Test-Path -LiteralPath $backendEnvFile) {
            Write-Host 'Existing .env.backend preserved.'
        } else {
            $template = [IO.File]::ReadAllText((Join-Path $repoRoot 'backend.env.example'))
            $template = $template.Replace('replace_with_generated_application_key', (New-LocalPassword))
            [IO.File]::WriteAllText($backendEnvFile, $template, (New-Object Text.UTF8Encoding($false)))
            Write-Host 'Created ignored .env.backend with a random application access key.'
        }
    } else {
        if ($Action -ne 'Frontend') { Import-DatabaseEnvironment }
        if ($Action -in 'BackendUp', 'Frontend', 'FrontendUp') {
            Import-BackendEnvironment
        }
        if ($Action -in 'Backend', 'BackendUp', 'FrontendUp') {
            foreach ($name in 'AZURE_OPENAI_BASE_URL', 'AZURE_OPENAI_API_KEY', 'AZURE_OPENAI_DEPLOYMENT') {
                if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
                    throw "Set $name in this terminal before starting the backend. Do not put provider secrets in the frontend."
                }
            }
        }
        if ($Action -eq 'Backend') {
            & .\mvnw.cmd spring-boot:run
            if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE." }
        } elseif ($Action -eq 'Frontend') {
            $env:BACKEND_URL = 'http://127.0.0.1:8080'
            # The frontend proxy only needs the application access key.
            Remove-Item Env:AZURE_OPENAI_API_KEY -ErrorAction SilentlyContinue
            Push-Location (Join-Path $repoRoot 'frontend')
            try {
                & npm.cmd run dev
                if ($LASTEXITCODE -ne 0) { throw "Frontend exited with code $LASTEXITCODE." }
            } finally { Pop-Location }
        } else {
            Enable-DockerCli
            $composeArgs = @('compose', '--env-file', $envFile, '-f', (Join-Path $repoRoot 'compose.yaml'))
            switch ($Action) {
                'Up' { & docker @composeArgs up -d --wait --wait-timeout 180 mysql }
                'Down' { & docker @composeArgs --profile '*' down }
                'Status' { & docker @composeArgs --profile '*' ps }
                'BackendUp' { & docker @composeArgs up -d --build --wait --wait-timeout 240 backend }
                'BackendDown' { & docker @composeArgs stop backend }
                'FrontendUp' { & docker @composeArgs up -d --build --wait --wait-timeout 240 frontend }
                'FrontendDown' { & docker @composeArgs stop frontend }
                'Logs' { & docker @composeArgs logs --tail 100 $Service }
                'Restart' {
                    $containerId = & docker @composeArgs ps --all --quiet $Service
                    if ($LASTEXITCODE -ne 0 -or -not $containerId) {
                        throw 'Start the application with FrontendUp before restarting a service.'
                    }
                    & docker @composeArgs restart $Service
                    if ($LASTEXITCODE -ne 0) { throw "Could not restart $Service." }
                    $timer = [Diagnostics.Stopwatch]::StartNew()
                    do {
                        $health = & docker inspect --format '{{.State.Health.Status}}' $containerId
                        if ($LASTEXITCODE -ne 0) { throw "Could not inspect $Service health." }
                        if ($health -eq 'healthy') { break }
                        if ($timer.Elapsed.TotalSeconds -ge 180) {
                            throw "$Service did not become healthy within 180 seconds. Use the Logs action to inspect it."
                        }
                        Start-Sleep -Seconds 1
                    } while ($true)
                    Write-Host "$Service restarted and is healthy."
                }
            }
            if ($LASTEXITCODE -ne 0) { throw "Docker Compose exited with code $LASTEXITCODE." }
        }
    }
} finally {
    Pop-Location
}
