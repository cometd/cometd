node {
    // System Dependent Locations
    def mvnTool = tool name: 'maven3', type: 'hudson.tasks.Maven$MavenInstallation'
    def jdk8 = tool name: 'jdk8', type: 'hudson.model.JDK'

    // Environment
    List mvnEnv8 = ["PATH+MVN=${mvnTool}/bin", "PATH+JDK=${jdk8}/bin", "JAVA_HOME=${jdk8}/", "MAVEN_HOME=${mvnTool}"]
    mvnEnv8.add("MAVEN_OPTS=-Xms256m -Xmx1024m -Djava.awt.headless=true")

    stage('Checkout') {
        checkout scm
    }

    stage('Build JDK 8') {
        withEnv(mvnEnv8) {
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

//    stage('Build JDK 8 - Jetty 10.0.x') {
//        withEnv(mvnEnv8) {
//            sh "mvn -B clean install -Dmaven.test.failure.ignore=true -Djetty-version=10.0.0-SNAPSHOT"
//            // Report failures in the jenkins UI
//            step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])
//        }
//    }

    stage('Javadoc') {
        withEnv(mvnEnv8) {
            sh "mvn -B javadoc:javadoc"
        }
    }
}
