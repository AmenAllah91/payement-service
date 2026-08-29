pipeline {
    agent any

    options {
        buildDiscarder(
            logRotator(
                daysToKeepStr: '10',
                numToKeepStr: '5',
                artifactNumToKeepStr: '1'
            )
        )
        timeout(time: 1, unit: 'HOURS')
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
    }

    environment {
        DOCKER_IMAGE = 'kamdigisdocker/payment-service'
        IMAGE_TAG = 'prod'
        SERVICE_NAME = 'payment-service'
        USER_CREDENTIALS= credentials('jenkins-docker')
        VPS_USER = credentials('production-vps')
        VPS_IP = '51.178.55.238'
    }

    stages {
        stage('Build docker image') {
            steps {
                sh 'docker build -t $DOCKER_IMAGE:$IMAGE_TAG .'
            }
        }
        stage('Push docker image') {
            steps {
                sh "docker login -u ${USER_CREDENTIALS_USR} -p ${USER_CREDENTIALS_PSW} docker.io"
                sh 'docker push $DOCKER_IMAGE:$IMAGE_TAG'
            }
        }
        stage('Perform Service Update') {
                    steps {
                        sshCommand remote: [
                          name: 'remote-vm',
                          host: "${VPS_IP}",
                          user: "${VPS_USER_USR}",
                          password: "${VPS_USER_PSW}",
                          allowAnyHosts: true
                        ], command: """
                          echo ${USER_CREDENTIALS_PSW} | docker login -u ${USER_CREDENTIALS_USR} --password-stdin
                          cd workspace-yosales && docker compose pull ${SERVICE_NAME} && docker compose down ${SERVICE_NAME} && docker compose up -d ${SERVICE_NAME}
                        """
                    }
                }
    }
}
