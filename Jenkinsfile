node {

  def builds = [:]

  builds['Build JDK 9 - Jetty 9.2.x'] = getBuild(null, true)

  builds['Build JDK 9 - Jetty 9.3.x'] = getBuild("9.3.22.v20171030", false)

  builds['Build JDK 9 - Jetty 9.4.x'] = getBuild("9.4.8.v20171121", false)

  parallel builds

}

def getBuild(jettyVersion, runJacoco) {
  return {
    node {

      // System Dependent Locations
      def mvnTool = tool name: 'maven3', type: 'hudson.tasks.Maven$MavenInstallation'
      def jdk9 = tool name: 'jdk9', type: 'hudson.model.JDK'

      // Environment
      List mvnEnv9 = ["PATH+MVN=${mvnTool}/bin", "PATH+JDK=${jdk9}/bin", "JAVA_HOME=${jdk9}/", "MAVEN_HOME=${mvnTool}"]
      mvnEnv9.add("MAVEN_OPTS=-Xms256m -Xmx1024m -Djava.awt.headless=true")

      stage('Checkout') {
        checkout scm
      }

      stage("Build $jettyVersion") {
        withEnv(mvnEnv9) {
          timeout(time: 1, unit: 'HOURS') {
            if (jettyVersion != null) {
              sh "mvn -B clean install -Dmaven.test.failure.ignore=true -Djetty-version=$jettyVersion"
            } else {
              sh "mvn -B clean install -Dmaven.test.failure.ignore=true"
            }

            junit testResults: '**/target/surefire-reports/TEST-*.xml'
            // Collect the JaCoCo execution results.
            if (runJacoco) {
              step([$class          : 'JacocoPublisher',
                    exclusionPattern: '**/org/webtide/**,**/org/cometd/benchmark/**,**/org/cometd/examples/**',
                    execPattern     : '**/target/jacoco.exec',
                    classPattern    : '**/target/classes',
                    sourcePattern   : '**/src/main/java'])
            }
          }
        }
        withEnv(mvnEnv9) {
          timeout(time: 5, unit: 'MINUTES') {
            sh "mvn -B javadoc:javadoc"
          }
        }
      }
    }
  }
}

