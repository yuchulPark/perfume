param([ValidateSet('Prepare', 'Verify', 'Stop')][string]$Action = 'Verify')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$testRoot = Join-Path $projectRoot 'target\support-local'
$clusterPath = Join-Path $testRoot 'data'
$postgresBin = 'C:\Program Files\PostgreSQL\17\bin'
$env:PGCONNECT_TIMEOUT = '5'
$env:PGSSLMODE = 'disable'
$env:PGGSSENCMODE = 'disable'
$psqlArguments = @('-X', '-h', '127.0.0.1', '-p', '26432', '-U', 'perfume_support_test', '-d', 'postgres', '-At', '-v', 'ON_ERROR_STOP=1')

function Assert-IsolatedCluster {
    $actualPath = & "$postgresBin\psql.exe" @psqlArguments -c 'SHOW data_directory'
    if ($LASTEXITCODE -ne 0 -or !$actualPath -or !(Test-Path -LiteralPath $clusterPath)) { throw 'Isolated cluster verification failed' }
    if ((Resolve-Path -LiteralPath $actualPath.Trim()).Path -ne (Resolve-Path -LiteralPath $clusterPath).Path) {
        throw 'Port 26432 belongs to a different database. No changes are permitted.'
    }
}

if ($Action -eq 'Prepare') {
    New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
    $listener = Get-NetTCPConnection -State Listen -LocalPort 26432 -ErrorAction SilentlyContinue
    if (!$listener) {
        if (!(Test-Path -LiteralPath (Join-Path $clusterPath 'PG_VERSION'))) {
            if ((Test-Path -LiteralPath $clusterPath) -and (Get-ChildItem -LiteralPath $clusterPath -Force | Select-Object -First 1)) {
                throw 'Refusing to initialize a nonempty directory'
            }
            & "$postgresBin\initdb.exe" -D $clusterPath -U perfume_support_test --encoding=UTF8 --no-locale --auth-local=trust --auth-host=trust | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'initdb failed' }
        } else {
            $marker = Join-Path $testRoot 'isolation-marker.txt'
            if (!(Test-Path -LiteralPath $marker) -or (Get-Content -LiteralPath $marker -Raw).Trim() -ne $clusterPath) { throw 'Unknown existing cluster; no startup permitted' }
        }
        # Wait only for pg_ctl itself, not its long-lived PostgreSQL descendant.
        $startArgs = @('-D', ('"' + $clusterPath + '"'), '-l', ('"' + (Join-Path $testRoot 'postgres.log') + '"'), '-o', '"-h 127.0.0.1 -p 26432"', '-w', '-t', '20', 'start')
        $starter = Start-Process -FilePath "$postgresBin\pg_ctl.exe" -ArgumentList $startArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testRoot 'pg-start.out.log') -RedirectStandardError (Join-Path $testRoot 'pg-start.err.log')
        $null = $starter.Handle # Retain the process handle before a short-lived pg_ctl exits.
        if (!$starter.WaitForExit(25000)) { throw 'Isolated PostgreSQL startup timed out' }
        $starter.Refresh()
        # Windows PowerShell can return a null ExitCode; the physical SQL probe below remains mandatory.
        if ($null -ne $starter.ExitCode -and $starter.ExitCode -ne 0) { throw 'Isolated PostgreSQL startup failed' }
    }
    Assert-IsolatedCluster
    $database = & "$postgresBin\psql.exe" @psqlArguments -c "SELECT datname FROM pg_database WHERE datname='perfume_support_test'"
    if ($LASTEXITCODE -ne 0) { throw 'Database name verification failed' }
    if (!$database) {
        & "$postgresBin\createdb.exe" -h 127.0.0.1 -p 26432 -U perfume_support_test perfume_support_test
        if ($LASTEXITCODE -ne 0) { throw 'Isolated test database creation failed' }
    }
    Set-Content -LiteralPath (Join-Path $testRoot 'isolation-marker.txt') -Value $clusterPath -Encoding UTF8
    Write-Output 'Isolated support test DB prepared: 127.0.0.1:26432/perfume_support_test (no Docker volumes).'
} else {
    Assert-IsolatedCluster
    if ($Action -eq 'Stop') {
        & "$postgresBin\pg_ctl.exe" -D $clusterPath -w -t 20 -m fast stop | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Isolated test PostgreSQL stop failed' }
        Write-Output 'Only the verified support test cluster was stopped; no data was deleted.'
    } else { Write-Output 'Physical cluster isolation verified.' }
}
