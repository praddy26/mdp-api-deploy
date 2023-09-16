#!groovy
@Library('jenkins-pipeline-shared@master') _

// Job specific variables
def slack_channel = 'devops_jenkins_notifications'
def stackName = 'edpapi'

// Common info for all edp-config stacks
def bucketName = "sunpower-sdp-devops"
def envMap = [
    'dev': [
        'bucket_destination_for_cf_template': 'cf-templates/develop',
        'bucket_destination_for_archived_template': 'pipelines/develop'
    ],
    'qa': [
        'bucket_destination_for_cf_template': 'cf-templates/release',
        'bucket_destination_for_archived_template': 'pipelines/release'
    ],
    'uat': [
        'bucket_destination_for_cf_template': 'cf-templates/master',
        'bucket_destination_for_archived_template': 'pipelines/master'
    ]
]
def agentLabel = "edp-config-${stackName}"

pipeline {

  agent {
    kubernetes {
      label "${agentLabel}"
      defaultContainer 'jnlp'
      yaml """
        apiVersion: v1
        kind: Pod
        metadata:
          labels:
            docker: true
        spec:
          containers:
          - name: git
            image: alpine/git
            command:
            - cat
            tty: true
          - name: node
            image: node:12.19-alpine
            command:
            - cat
            tty: true
        """
        }
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '25'))
        timestamps()
        skipDefaultCheckout true
        timeout(time: 30, unit: 'MINUTES')
    }

    environment {
        AWS_DEFAULT_REGION="us-west-2"
        AWS_CRED = "EDP_DEV_JENKINS"
    }

    stages {
        stage ('Checkout') {
            when {
                anyOf { branch 'develop'; branch 'release/*'; branch 'master' }
            }
            steps {
                container('git') {
                    script {
                        def repo = checkout([$class: 'GitSCM',
                            branches: [[ name: env.BRANCH_NAME ]],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [],
                            submoduleCfg: [],
                            userRemoteConfigs: scm.userRemoteConfigs
                        ])
                        commit = repo.GIT_COMMIT.take(7)
                        branch = repo.GIT_BRANCH.split('/')[1]
                        env.COMMIT_ID = commit
                        sendNotifications 'STARTED', "Job: '${env.JOB_NAME}',VERSION: 'commit-${commit}-build-${env.BUILD_NUMBER}'", slack_channel
                        bitbucketStatusNotify (
                            buildName: "${env.JOB_NAME}",
                            buildState: 'INPROGRESS',
                            repoSlug: 'edp-config',
                            commitId: env.COMMIT_ID
                        )
                    }
                }
            }
        }

        stage('NPM install') {
            when {
                anyOf { branch 'develop'; branch 'release/*'; branch 'master' }
            }
            steps {
                container('node') {
                    script {
                        sh """
                        npm install
                        """
                    }
                }
            }
        }

        stage ('NPM lint and build'){
            when {
                anyOf { branch 'develop'; branch 'release/*'; branch 'master' }
            }
            steps {
                container('node') {
                    script {
                        withAWS(region: env.AWS_DEFAULT_REGION, credentials: env.AWS_CRED) {
                            //Shell build step
                            sh """
                            ./build/configs/generate-config.sh ${stackName} > config.json
                            cat config.json
                            BUILD_TAG=`echo "$BUILD_TAG" | sed 's/%2F1.0.0//'` CONFIG=config.json npm run build
                            """
                        }
                    }
                }
            }
        }

        stage('S3 upload artifacts') {
            when {
                anyOf { branch 'develop'; branch 'release/*'; branch 'master' }
            }
            steps {
                container('node') {
                    script {
                        if (branch == "develop") {
                            deployEnv = "dev"
                        } else if (branch =~ "release/*") {
                            deployEnv = "qa"
                        } else if (branch  == "master") {
                            deployEnv = "uat"
                        } else {
                            error("deploy env not found")
                        }
                        def cf_bucket_path = envMap[deployEnv].bucket_destination_for_cf_template
                        def zip_bucket_path = envMap[deployEnv].bucket_destination_for_archived_template
                        def templates_include = "${stackName}/*.*"
                        def zip_name = "${stackName}.zip"

                        withAWS(region: env.AWS_DEFAULT_REGION, credentials: env.AWS_CRED) {
                            // upload built to s3 bucket
                            s3Upload(bucket:"${bucketName}",
                                path:"${cf_bucket_path}",
                                includePathPattern:"${templates_include}",
                                workingDir:'templates',
                                metadatas:["jenkins-build-tag:${BUILD_TAG}","commit-id:${commit}","jenkins-build-number:${env.BUILD_NUMBER}"])
                            s3Upload(
                                bucket:"${bucketName}",
                                path:"${zip_bucket_path}",
                                includePathPattern:"${zip_name}",
                                workingDir:'dist/stacks',
                                metadatas:["jenkins-build-tag:${BUILD_TAG}","commit-id:${commit}","jenkins-build-number:${env.BUILD_NUMBER}"])
                        }
                    }
                }
            }
        }
    }

    post {
        success{
            sendNotifications currentBuild.result, "Job: '${env.JOB_NAME}',VERSION: 'commit-${commit}-build-${env.BUILD_NUMBER}'", slack_channel
            bitbucketStatusNotify (
                buildName: "${env.JOB_NAME}",
                buildState: 'SUCCESSFUL',
                repoSlug: 'edp-config',
                commitId: env.COMMIT_ID
            )
            archiveArtifacts artifacts: 'dist/stacks/*.zip', onlyIfSuccessful: true
        }
        unsuccessful{
            sendNotifications currentBuild.result, "Job: '${env.JOB_NAME}',VERSION: 'commit-${commit}-build-${env.BUILD_NUMBER}'", slack_channel
            bitbucketStatusNotify (
                buildName: "${env.JOB_NAME}",
                buildState: 'FAILED',
                repoSlug: 'edp-config',
                commitId: env.COMMIT_ID
            )
        }
    }
}