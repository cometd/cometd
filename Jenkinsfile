node {

  def builds = [:]

  builds['Build JDK 9 - Jetty 9.2.x'] = getBuild(null, true)

  builds['Build JDK 9 - Jetty 9.3.x'] = getBuild("9.3.24.v20180605", false)

  builds['Build JDK 9 - Jetty 9.4.x'] = getBuild("9.4.12.v20180829", false)

  parallel builds

}

def getBuild(jettyVersion, runJacoco) {
  return {
    node("linux") {

      // System Dependent Locations
      def mvnTool = tool name: 'maven3', type: 'hudson.tasks.Maven$MavenInstallation'
      def jdk='jdk9'
      def jdk9 = tool name: "$jdk", type: 'hudson.model.JDK'
      def settingsName = 'oss-settings.xml'
      def localRepo = "${env.JENKINS_HOME}/${env.EXECUTOR_NUMBER}"
      def mvnName = 'maven3'

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
              withMaven(
                      maven: mvnName,
                      jdk: "$jdk",
                      mavenOpts:"-Xms256m -Xmx1024m -Djava.awt.headless=true",
                      publisherStrategy: 'EXPLICIT',
                      globalMavenSettingsConfig: settingsName,
                      mavenLocalRepo: localRepo) {
                sh "mvn -B clean install -Dmaven.test.failure.ignore=true -e -Djetty-version=$jettyVersion"
              }
            } else {
              withMaven(
                      maven: mvnName,
                      jdk: "$jdk",
                      mavenOpts:"-Xms256m -Xmx1024m -Djava.awt.headless=true",
                      publisherStrategy: 'EXPLICIT',
                      globalMavenSettingsConfig: settingsName,
                      mavenLocalRepo: localRepo) {
                sh "mvn -B clean install -Dmaven.test.failure.ignore=true -e"
              }
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
            withMaven(
                    maven: mvnName,
                    jdk: "$jdk",
                    mavenOpts:"-Xms256m -Xmx1024m -Djava.awt.headless=true",
                    publisherStrategy: 'EXPLICIT',
                    globalMavenSettingsConfig: settingsName,
                    mavenLocalRepo: localRepo) {
              sh "mvn -B javadoc:javadoc -e -T4"
            }
          }
        }
      }
    }
  }
}

