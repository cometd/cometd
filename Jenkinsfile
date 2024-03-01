#!groovy

pipeline {
  agent none
  // Save some I/O during the build.
  options { 
    durabilityHint('PERFORMANCE_OPTIMIZED') 
    buildDiscarder logRotator( numToKeepStr: '50' )
  }

  stages {
    stage('CometD Builds') {
      matrix {
        axes {
          axis {
            name 'JDK'
            values 'jdk11', 'jdk17', 'jdk21'
          }
        }
        stages {
          stage('Build CometD') {
            agent { node { label 'linux' } }
            steps {
              timeout(time: 1, unit: 'HOURS') {
                mavenBuild("${env.JDK}", "clean install")
              }
              timeout(time: 15, unit: 'MINUTES') {
                mavenBuild("${env.JDK}", "javadoc:javadoc")
              }
              recordIssues id: "analysis-${env.JDK}", name: "Static Analysis ${env.JDK}", aggregatingResults: true, enabledForFailure: true,
                           tools: [mavenConsole(), java(), checkStyle(), javaDoc()], skipPublishingChecks: true, skipBlames: true
              recordCoverage name: "Coverage ${env.JDK}", id: "coverage-${env.JDK}", tools: [[parser: 'JACOCO']], sourceCodeRetention: 'LAST_BUILD',
                              sourceDirectories: [[path: 'src/main/java']]
            }
          }
        }
      }
    }
  }
}
/**
 * Performs a Maven build.
 *
 * @param jdk the jdk tool name (in jenkins) to use for this build
 * @param cmdline the command line in "<profiles> <goals> <properties>"`format.
 * @param consoleParsers array of console parsers to run
 */
def mavenBuild(jdk, cmdline) {
  script {
    try {
      withEnv(["JAVA_HOME=${tool "$jdk"}",
               "PATH+MAVEN=${env.JAVA_HOME}/bin:${tool "maven3"}/bin",
               "MAVEN_OPTS=-Xms2g -Xmx4g -Djava.awt.headless=true"]) {
        configFileProvider([configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS')]) {
          sh "mvn -s $GLOBAL_MVN_SETTINGS -Dmaven.repo.local=.repository -V -B -e $cmdline"
        }
      }
    }
    finally {
      junit testResults: '**/target/surefire-reports/*.xml,**/target/invoker-reports/TEST*.xml'
    }
  }
}
