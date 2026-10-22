param(
    [ValidateSet('true', 'false')][string]$AuthEnabled = 'true',
    [ValidateSet(8081, 18081)][int]$Port = 8081
)
$ErrorActionPreference = 'Stop'
$authProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$authJar = Join-Path $authProjectRoot 'target\perfume-0.0.1-SNAPSHOT.jar'
$authTestClasses = Join-Path $authProjectRoot 'target\test-classes'
$authDist = Join-Path $authProjectRoot 'frontend\dist'
if (-not (Test-Path -LiteralPath $authJar) -or -not (Test-Path -LiteralPath (Join-Path $authDist 'index.html'))) {
    throw 'Build the local JAR/test classes and frontend first. See docs/auth-local-verification.md.'
}
$authEnvironment = @{
    PERFUME_AUTH_LOCAL_CHECK = 'true'
    PERFUME_AUTH_ENABLED = $AuthEnabled
    DB_URL = 'jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev'
}
$authPreviousEnvironment = @{}
foreach ($authKey in $authEnvironment.Keys) {
    $authPreviousEnvironment[$authKey] = [Environment]::GetEnvironmentVariable($authKey, 'Process')
}
Push-Location $authProjectRoot
try {
    foreach ($authKey in $authEnvironment.Keys) { [Environment]::SetEnvironmentVariable($authKey, $authEnvironment[$authKey], 'Process') }
    $authStaticLocation = 'file:' + ($authDist -replace '\\', '/') + '/'
    $authArguments = @(
        ('-Dloader.path=' + $authTestClasses), '-Dloader.main=com.perfume.auth.AuthLocalPreviewServer', '-cp', $authJar,
        'org.springframework.boot.loader.launch.PropertiesLauncher', '--spring.profiles.active=auth-dev',
        '--perfume.auth.local-preview=true', ('--perfume.auth.enabled=' + $AuthEnabled),
        '--server.address=127.0.0.1', ('--server.port=' + $Port),
        ('--spring.web.resources.static-locations=' + $authStaticLocation),
        '--spring.datasource.url=jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev',
        '--spring.datasource.username=perfume_auth_dev', '--spring.datasource.password=',
        '--spring.jpa.hibernate.ddl-auto=validate', '--spring.sql.init.mode=never', '--server.servlet.session.cookie.secure=false',
        '--perfume.ai.search.enabled=false', '--perfume.ai.recommend.enabled=false',
        '--perfume.ai.embedding.batch.enabled=false', '--perfume.ai.embedding.auto.enabled=false',
        '--scentrev.brand-import=false', '--debug=false'
    )
    & java @authArguments
    if ($LASTEXITCODE -ne 0) { throw "Local verification server stopped with exit code $LASTEXITCODE" }
} finally {
    foreach ($authKey in $authPreviousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($authKey, $authPreviousEnvironment[$authKey], 'Process') }
    Pop-Location
}
