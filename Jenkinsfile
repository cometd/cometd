#!groovy

def oss = ["linux"]
def jdks = ["jdk8", "jdk9", "jdk10", "jdk11"]

def builds = [:]
for (def os in oss) {
  for (def jdk in jdks) {
    builds[os + "_" + jdk] = newBuild(os, jdk)
  }
}

parallel builds

def newBuild(os, jdk) {
  return {
    node( os ) {
      def mvnName = 'maven3'
      def mvnTool = tool name: "${mvnName}", type: 'hudson.tasks.Maven$MavenInstallation'
      def jdkTool = tool name: "${jdk}", type: 'hudson.model.JDK'
      List mvnEnv = ["PATH+MVN=${mvnTool}/bin", "PATH+JDK=${jdkTool}/bin", "JAVA_HOME=${jdkTool}/", "MAVEN_HOME=${mvnTool}"]
      mvnEnv.add( "MAVEN_OPTS=-Xms256m -Xmx1024m -Djava.awt.headless=true" )
      def settingsName = 'oss-settings.xml'
      def localRepo = "${env.JENKINS_HOME}/${env.EXECUTOR_NUMBER}"
      def mavenOpts = '-Xms1g -Xmx2g -Djava.awt.headless=true'

      stage( "Checkout - ${jdk}" ) {
        checkout scm
      }

      stage( "Build - ${jdk}" ) {
        withEnv( mvnEnv ) {
          timeout( time: 1, unit: 'HOURS' ) {
            withMaven(
                    maven: mvnName,
                    jdk: "$jdk",
                    publisherStrategy: 'EXPLICIT',
                    globalMavenSettingsConfig: settingsName,
                    mavenOpts: mavenOpts,
                    mavenLocalRepo: localRepo) {
              sh "mvn -V -B clean install -Dmaven.test.failure.ignore=true -e"
            }
            // Report failures in the jenkins UI.
            junit testResults:'**/target/surefire-reports/TEST-*.xml'
            // Collect the JaCoCo execution results.
            jacoco exclusionPattern: '**/org/webtide/**,**/org/cometd/benchmark/**,**/org/cometd/examples/**',
                   execPattern     : '**/target/jacoco.exec',
                   classPattern    : '**/target/classes',
                   sourcePattern   : '**/src/main/java'
          }
        }
      }

      stage( "Javadoc - ${jdk}" ) {
        withEnv( mvnEnv ) {
          timeout( time: 5, unit: 'MINUTES' ) {
            withMaven(
                    maven: mvnName,
                    jdk: "$jdk",
                    publisherStrategy: 'EXPLICIT',
                    globalMavenSettingsConfig: settingsName,
                    mavenOpts: mavenOpts,
                    mavenLocalRepo: localRepo) {
              sh "mvn -V -B javadoc:javadoc"
            }
          }
        }
      }
    }
  }
}