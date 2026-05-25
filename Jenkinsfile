#!/usr/bin/env groovy


def java = null
def maven = null
def mavenEnv = null
def version = null
def mvnLocalHome = "/opt/m2repos/pulsar-vault-secrets-provider.docker"
def img = null
def pom = null

/***
 * A manual build will have UserIdCause. Triggered builds have different causes.
 ***/
def wasManuallyKickedOff() {
    return currentBuild.rawBuild.getCauses()[0].class.toString().endsWith('UserIdCause')
}

/***
 * We only release from master. Also, we do not want to push on every master build, but only those
 * that are manually-initiated. This will prevent failed builds due to Jenkins trying to push artifacts
 * for previously-released versions when builds are triggered by things like branch indexing.
 ***/
def isRelease() {
    return env.BRANCH_NAME == 'master' && wasManuallyKickedOff()
}

def currentVersion(mavenEnv) {
    withEnv(mavenEnv) {
        pom = readMavenPom file: 'pom.xml'
        def version = sh script: "echo ${pom.getVersion().trim()} | grep -Eo '[0-9\\.]+'", returnStdout: true
        return version.trim()
    }
}

def createVersion(version) {
    version = version + (isRelease() ? '' : "-${env.BRANCH_NAME}-SNAPSHOT")
    return version
}

pipeline {
    agent {
        label 'docker'
    }
    stages {
        stage('Initialize') {
            steps {
                script {
                    checkout scm
                    java = tool name: 'Eclipse Temurin openjdk17-17.0.6_10', type: 'jdk'
                    maven = tool name: 'maven 3.9.0', type: 'maven'
                    mavenEnv = ["MVN_HOME=${maven}", "JAVA_HOME=${java}", "PATH+MAVEN=${maven}/bin"]
                    sh 'echo ${JAVA_HOME}'
                    version = createVersion(currentVersion(mavenEnv))
                }
            }
        }
        stage('Build and unit test') {
            steps {
                script {
                    withEnv(mavenEnv) {
                        sh "mvn clean test install -B -U -e -Dmaven.repo.local=${mvnLocalHome}"
                    }
                }
            }
        }
        stage('Push') {
            steps {
                script {
                    withEnv(mavenEnv) {
                        if (isRelease()) {
                            sh "mvn -Dresume=false release:clean release:prepare release:perform -Dmaven.javadoc.skip=true -DskipTests -Dmaven.repo.local=${mvnLocalHome}"
                        } else {
                            sh "mvn versions:set -DnewVersion=${version} -Dmaven.repo.local=${mvnLocalHome}"
                            sh "mvn deploy -DskipTests -Dmaven.repo.local=${mvnLocalHome}"
                        }
                    }
                }
            }
        }
//         stage('Build Image and push') {
//             steps {
//                 script{
//                     docker.withRegistry('https://docker-internal-latest.overstock.com:8087','docker-ostk') {
//                         pom = readMavenPom file: 'pom.xml'
//                         sh script: "echo Image name :: ${pom.getVersion().trim()}  de/pulsar-all_vault-provider:3.0.16-latest"
//                         customImage = docker.build("de/pulsar-all_vault-provider:3.0.16-latest","--build-arg APP_VERSION=${pom.getVersion().trim()} ./docker/pulsar/Dockerfile")
//                         customImage.push("latest")
//                     }
//
//                     if (isRelease()) {
//                         pom = readMavenPom file: 'pom.xml'
//                         docker.withRegistry('https://docker.overstock.com:8085', 'docker-user') {
//                             def prod_version = "${pom.getVersion().trim()}.${env.BUILD_NUMBER}".replace('-SNAPSHOT','')
//                             sh "echo ${prod_version}"
//                             customImage = docker.build("de/pulsar-all_vault-provider:3.0.16-${prod_version}","--build-arg APP_VERSION=${pom.getVersion().trim()} .")
//                             customImage.push()
//                         }
//                     }
//                 }
//             }
//         }
    }
}