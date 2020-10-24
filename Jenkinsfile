#!groovy

pipeline {
  agent any
  // save some io during the build
  options { durabilityHint( 'PERFORMANCE_OPTIMIZED' ) }
  stages {
    stage( 'Cometd all build' ) {
      matrix {
        axes {
          axis {
            name 'JDK'
            values 'jdk8', 'jdk11', 'jdk14'
          }
        }
        stages {
          stage( 'Build Cometd' ) {
            agent { node { label 'linux' } }
            steps {
              container('jetty-build') {
                timeout( time: 1, unit: 'HOURS' ) {
                  mavenBuild( "${env.JDK}", "clean install", "maven3", [[parserName: 'Maven'], [parserName: 'Java']] )
                  // Collect the JaCoCo execution results.
                  jacoco exclusionPattern: '**/org/webtide/**,**/org/cometd/benchmark/**,**/org/cometd/examples/**',
                         execPattern: '**/target/jacoco.exec',
                         classPattern: '**/target/classes',
                         sourcePattern: '**/src/main/java'
                }
                timeout( time: 15, unit: 'MINUTES' ) {
                  mavenBuild( "${env.JDK}", "javadoc:javadoc", "maven3", null )
                }
              }
            }
          }
        }
      }
    }
  }
}
/**
 * To other developers, if you are using this method above, please use the following syntax.
 *
 * mavenBuild("<jdk>", "<profiles> <goals> <plugins> <properties>"
 *
 * @param jdk the jdk tool name (in jenkins) to use for this build
 * @param cmdline the command line in "<profiles> <goals> <properties>"`format.
 * @param consoleParsers array of console parsers to run
 */
def mavenBuild(jdk, cmdline, mvnName, consoleParsers) {
  script {
    try {
      withEnv(["JAVA_HOME=${ tool "$jdk" }",
               "PATH+MAVEN=${env.JAVA_HOME}/bin:${tool "$mvnName"}/bin",
               "MAVEN_OPTS=-Xms2g -Xmx4g -Djava.awt.headless=true"]) {
        configFileProvider(
                [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS')]) {
          sh "mvn -s $GLOBAL_MVN_SETTINGS -Dmaven.repo.local=.repository -V -B -e $cmdline"
        }
      }
    }
    finally
    {
      junit testResults: '**/target/surefire-reports/*.xml,**/target/invoker-reports/TEST*.xml'
      if(consoleParsers!=null){
        warnings consoleParsers: consoleParsers
      }
    }
  }
}
