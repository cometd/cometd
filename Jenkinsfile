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
    node(os) {
      def mvnName = 'maven3.5'
      def settingsName = 'oss-settings.xml'
      def mvnOpts = '-Xms256m -Xmx1024m -Djava.awt.headless=true'

      stage("Checkout - ${jdk}") {
        checkout scm
      }

      stage("Build - ${jdk}") {
        timeout(time: 1, unit: 'HOURS') {
          withMaven(maven: mvnName,
                  jdk: "${jdk}",
                  publisherStrategy: 'EXPLICIT',
                  globalMavenSettingsConfig: settingsName,
                  mavenOpts: mvnOpts) {
            sh "mvn -V -B clean install -Dmaven.test.failure.ignore=true"
          }
          // Report failures in the jenkins UI.
          junit testResults: '**/target/surefire-reports/TEST-*.xml'
          // Collect the JaCoCo execution results.
          jacoco exclusionPattern: '**/org/webtide/**,**/org/cometd/benchmark/**,**/org/cometd/examples/**',
                  execPattern: '**/target/jacoco.exec',
                  classPattern: '**/target/classes',
                  sourcePattern: '**/src/main/java'
        }
      }

      stage("Javadoc - ${jdk}") {
        timeout(time: 5, unit: 'MINUTES') {
          withMaven(maven: mvnName,
                  jdk: "${jdk}",
                  publisherStrategy: 'EXPLICIT',
                  globalMavenSettingsConfig: settingsName,
                  mavenOpts: mvnOpts) {
            sh "mvn -V -B javadoc:javadoc"
          }
        }
      }
    }
  }
}
