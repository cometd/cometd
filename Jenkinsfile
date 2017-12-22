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

  stage('Build JDK 9 - Jetty 9.4.x') {
    withEnv(mvnEnv9) {
      timeout(time: 1, unit: 'HOURS') {
        sh "mvn -B clean install -Dmaven.test.failure.ignore=true"
        // Report failures in the jenkins UI.
        step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])
        // Collect the JaCoCo execution results.
        step([$class: 'JacocoPublisher',
          exclusionPattern: '**/org/webtide/**,**/org/cometd/benchmark/**,**/org/cometd/examples/**',
          execPattern: '**/target/jacoco.exec',
          classPattern: '**/target/classes',
          sourcePattern: '**/src/main/java'])
      }
    }
  }

  stage('Javadoc') {
    withEnv(mvnEnv9) {
      timeout(time: 5, unit: 'MINUTES') {
        sh "mvn -B javadoc:javadoc"
      }
    }
  }
}
