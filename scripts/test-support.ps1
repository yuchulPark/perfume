param([switch]$Postgres, [switch]$Browser, [switch]$Serve, [switch]$All)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
$output = Join-Path $root 'target\support-local'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$originalEnvironment = @{}
foreach ($name in @('SUPPORT_TEST_PGDATA', 'SUPPORT_TEST_PASSWORD', 'SUPPORT_LOCAL_TEST')) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
$server = $null
$startedDatabase = $false

function Invoke-TestMaven([string[]]$MavenArguments, [string]$LogName) {
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue' # Native JVM warnings on stderr are not PowerShell exceptions.
        & (Join-Path $root 'mvnw.cmd') -B -f (Join-Path $root 'target\support-validation-pom.xml') @MavenArguments *> (Join-Path $output $LogName)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    Get-Content (Join-Path $output $LogName) -Tail 16
    if ($code -ne 0) { throw "Maven failed; see ignored target/support-local/$LogName" }
}

try {
    # Same source, dependencies and test selection; only IDE-shared outputs are relocated.
    [xml]$pom = Get-Content -Raw -Encoding UTF8 (Join-Path $root 'pom.xml')
    $ns = New-Object Xml.XmlNamespaceManager($pom.NameTable)
    $namespace = 'http://maven.apache.org/POM/4.0.0'
    $ns.AddNamespace('m', $namespace)
    $build = $pom.SelectSingleNode('/m:project/m:build', $ns)
    foreach ($pair in @(@('directory', "$root/target/support-validation"), @('sourceDirectory', "$root/src/main/java"), @('testSourceDirectory', "$root/src/test/java"))) {
        $node = $pom.CreateElement($pair[0], $namespace); $node.InnerText = $pair[1].Replace('\','/'); [void]$build.AppendChild($node)
    }
    foreach ($triple in @(@('resources','resource', "$root/src/main/resources"), @('testResources','testResource', "$root/src/test/resources"))) {
        $container = $pom.CreateElement($triple[0], $namespace); $item = $pom.CreateElement($triple[1], $namespace)
        $node = $pom.CreateElement('directory', $namespace); $node.InnerText = $triple[2].Replace('\','/')
        [void]$item.AppendChild($node); [void]$container.AppendChild($item); [void]$build.AppendChild($container)
    }
    $plugin = $pom.CreateElement('plugin', $namespace)
    $escapedRoot = [Security.SecurityElement]::Escape($root.Replace('\','/'))
    $plugin.InnerXml = '<groupId xmlns="http://maven.apache.org/POM/4.0.0">org.apache.maven.plugins</groupId><artifactId xmlns="http://maven.apache.org/POM/4.0.0">maven-surefire-plugin</artifactId><configuration xmlns="http://maven.apache.org/POM/4.0.0"><workingDirectory>' + $escapedRoot + '</workingDirectory><systemPropertyVariables><debug>false</debug></systemPropertyVariables></configuration>'
    [void]$build.SelectSingleNode('m:plugins', $ns).AppendChild($plugin)
    $pom.Save((Join-Path $root 'target\support-validation-pom.xml'))

    if ($Postgres -or $Browser -or $Serve) {
        $startedDatabase = !(Get-NetTCPConnection -State Listen -LocalPort 26432 -ErrorAction SilentlyContinue)
        & (Join-Path $PSScriptRoot 'test-support-postgres.ps1') -Action Prepare
        $credentialPath = Join-Path $output 'test-admin.clixml'
        if (!(Test-Path -LiteralPath $credentialPath)) {
            $password = 'Test-' + [guid]::NewGuid().ToString() + 'Az!'
            $credential = New-Object System.Management.Automation.PSCredential('admin', (ConvertTo-SecureString $password -AsPlainText -Force))
            $credential | Export-Clixml -LiteralPath $credentialPath
        } else { $credential = Import-Clixml -LiteralPath $credentialPath }
        $env:SUPPORT_TEST_PASSWORD = $credential.GetNetworkCredential().Password
        $env:SUPPORT_TEST_PGDATA = Join-Path $output 'data'
    }
    $selection = if ($All) { @('test') } else { @('-Dtest=Support*Tests', 'test') }
    Invoke-TestMaven $selection 'java-tests.log'

    if ($Browser -or $Serve) {
        if (Get-NetTCPConnection -State Listen -LocalPort 18082 -ErrorAction SilentlyContinue) { throw 'Port 18082 is already occupied; no existing process will be replaced' }
        $classpathPath = Join-Path $output 'classpath.txt'
        Invoke-TestMaven @('dependency:build-classpath', '-DincludeScope=test', "-Dmdep.outputFile=$classpathPath") 'classpath-build.log'
        $classpath = 'target/support-validation/test-classes;target/support-validation/classes;' + (Get-Content -Raw $classpathPath).Trim().Replace('\','/')
        $arguments = '-Dfile.encoding=UTF-8' + "`n-Ddebug=false`n-classpath`n" + '"' + $classpath + '"' + "`ncom.perfume.support.SupportTestApplication`n"
        [IO.File]::WriteAllText((Join-Path $output 'java.args'), $arguments, (New-Object Text.UTF8Encoding($false)))
        $oldPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        Push-Location (Join-Path $root 'frontend')
        try {
            & npm.cmd run build *> (Join-Path $output 'frontend-build.log')
            if ($LASTEXITCODE -ne 0) { throw 'React build failed' }
        } finally { Pop-Location; $ErrorActionPreference = $oldPreference }
        $env:SUPPORT_LOCAL_TEST = 'true'
        $server = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList '@target/support-local/java.args' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output 'server.out.log') -RedirectStandardError (Join-Path $output 'server.err.log')
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            if ($server.HasExited) { throw 'Test server exited; inspect ignored server logs' }
            try {
                $reply = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:18082/api/support/config' -TimeoutSec 2
                if (($reply.Content | ConvertFrom-Json).enabled) { $ready = $true; break }
            } catch { }
            Start-Sleep -Milliseconds 500
        }
        if (!$ready) { throw 'Test server startup timed out' }
        if ($Browser) {
            $ErrorActionPreference = 'Continue'
            Push-Location (Join-Path $root 'frontend')
            try { & npm.cmd run test:support *> (Join-Path $output 'browser-tests.log'); $browserExit = $LASTEXITCODE }
            finally { Pop-Location; $ErrorActionPreference = $oldPreference }
            $browserLog = (Get-Content -Raw (Join-Path $output 'browser-tests.log')).Replace($env:SUPPORT_TEST_PASSWORD, '[redacted]')
            Set-Content (Join-Path $output 'browser-tests.log') $browserLog
            Write-Output $browserLog
            if ($browserExit -ne 0) { throw 'Browser support tests failed' }
        }
        if ($Serve) {
            Write-Output 'Isolated support preview: http://127.0.0.1:18082/recommend ; admin: /admin/support (Ctrl+C to stop).'
            Write-Output 'Use only the local DPAPI test credential documented in docs/support-chat.md, never the Ubuntu administrator password.'
            while (!$server.HasExited) { Start-Sleep -Seconds 1 }
        }
    }
} finally {
    if ($server -and !$server.HasExited) { $server.Kill(); $server.WaitForExit() }
    if ($startedDatabase) { & (Join-Path $PSScriptRoot 'test-support-postgres.ps1') -Action Stop }
    foreach ($name in $originalEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process') }
}
