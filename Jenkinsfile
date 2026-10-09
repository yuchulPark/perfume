def runPerfumeServer(String action) {
    withCredentials([
        string(credentialsId: 'perfume-ubuntu-host', variable: 'PERFUME_DEPLOY_HOST'),
        sshUserPrivateKey(credentialsId: 'perfume-ubuntu-ssh', keyFileVariable: 'PERFUME_SSH_KEY', usernameVariable: 'PERFUME_SSH_USER'),
        file(credentialsId: 'perfume-ubuntu-known-hosts', variable: 'PERFUME_KNOWN_HOSTS'),
        string(credentialsId: 'perfume-db-password', variable: 'PERFUME_DB_PASSWORD')
    ]) {
        withEnv(["PERFUME_DEPLOY_ACTION=${action}"]) {
            sh label: "Perfume server ${action}", script: '''
                set +x
                set -eu
                bash scripts/jenkins-ssh.sh "$PERFUME_DEPLOY_ACTION" "$PERFUME_REVISION"
            '''
        }
    }
}

pipeline {
    agent any

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    // Only this new job polls its configured main branch; no global Jenkins change.
    triggers {
        pollSCM('H/5 * * * *')
    }

    stages {
        stage('Checkout main') {
            steps {
                checkout scm
                script {
                    env.PERFUME_REVISION = sh(returnStdout: true, script: '''
                        set -eu
                        test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)"
                        git rev-parse HEAD
                    ''').trim()
                }
            }
        }

        stage('Deployment preflight') {
            steps {
                sh '''
                    set -eu
                    command -v docker >/dev/null
                    command -v ssh >/dev/null
                    command -v bash >/dev/null
                    docker compose version
                    docker info --format '{{.ServerVersion}}'
                '''
                script {
                    runPerfumeServer('check')
                }
            }
        }

        stage('Deployment guard tests') {
            steps {
                sh '''
                    set -eu
                    docker run --rm --volumes-from jenkins \
                        --user "$(id -u):$(id -g)" \
                        -w "$WORKSPACE" -e PYTHONDONTWRITEBYTECODE=1 \
                        python:3.12-slim \
                        python -m unittest discover -s tests/deployment -v
                '''
            }
        }

        stage('Spring Boot build and tests') {
            steps {
                sh '''
                    set -eu
                    docker build --target build \
                        --tag "perfume-ci-backend:$PERFUME_REVISION-$BUILD_NUMBER" .
                '''
            }
        }

        stage('React build and tests') {
            steps {
                sh '''
                    set -eu
                    docker build --target build \
                        --tag "perfume-ci-frontend:$PERFUME_REVISION-$BUILD_NUMBER" frontend
                '''
            }
        }

        stage('Deploy perfume applications') {
            steps {
                script {
                    runPerfumeServer('deploy')
                }
            }
        }
    }

    post {
        success {
            echo 'Perfume deployment completed: backend/frontend healthy; existing DB retained.'
        }
        failure {
            echo 'Perfume pipeline failed. Check the stage; DB initialization/restoration is manual.'
        }
    }
}
