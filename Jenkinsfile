#!groovy

pipeline {
  agent none
  // Save some I/O during the build.
  options { 
    durabilityHint("PERFORMANCE_OPTIMIZED")
    buildDiscarder logRotator( numToKeepStr: "5" )
  }

  stages {
    stage("CometD Builds") {
      parallel {
        stage("Javadocs") {
          agent { node { label "linux-light" } }
          steps {
            timeout(time: 15, unit: "MINUTES") {
              mavenBuild("jdk21", "clean install javadoc:javadoc", false)
            }
          }
        }
        stage("Java 21") {
          agent { node { label "linux-light" } }
          steps {
            timeout(time: 1, unit: "HOURS") {
              mavenBuild("jdk21", "clean install", true)
              recordIssues id: "analysis", name: "Static Analysis", aggregatingResults: true, enabledForFailure: true,
                      tools: [mavenConsole(), java(), checkStyle(), javaDoc()], skipPublishingChecks: true, skipBlames: true
              recordCoverage name: "Coverage", id: "coverage", tools: [[parser: "JACOCO"]], sourceCodeRetention: "LAST_BUILD",
                      sourceDirectories: [[path: "src/main/java"]]
            }
          }
        }
        stage("Java 17") {
          agent { node { label "linux-light" } }
          steps {
            timeout(time: 1, unit: "HOURS") {
              mavenBuild("jdk17", "clean install", true)
            }
          }
        }
        stage("Java 11") {
          agent { node { label "linux-light" } }
          steps {
            timeout(time: 1, unit: "HOURS") {
              mavenBuild("jdk11", "clean install", true)
            }
          }
        }
        stage("Java 8") {
          agent { node { label "linux-light" } }
          steps {
            timeout(time: 1, unit: "HOURS") {
              mavenBuild("jdk8", "clean install", true)
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
def mavenBuild(jdk, cmdline, withTests) {
  script {
    try {
      withEnv(["JAVA_HOME=${tool "$jdk"}",
               "PATH+MAVEN=${env.JAVA_HOME}/bin:${tool "maven3"}/bin",
               "MAVEN_OPTS=-Xms2g -Xmx4g -Djava.awt.headless=true"]) {
        configFileProvider([configFile(fileId: "oss-settings.xml", variable: "GLOBAL_MVN_SETTINGS")]) {
          sh "mvn -s $GLOBAL_MVN_SETTINGS -Dmaven.repo.local=.repository -V -B -e $cmdline"
        }
      }
    }
    finally {
      if (withTests) {
        junit testResults: "**/target/surefire-reports/*.xml,**/target/invoker-reports/TEST*.xml"
      }
    }
  }
}
