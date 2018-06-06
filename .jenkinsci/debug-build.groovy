#!/usr/bin/env groovy

def doDebugBuild(coverageEnabled=false) {
  def dPullOrBuild = load ".jenkinsci/docker-pull-or-build.groovy"
  def manifest = load ".jenkinsci/docker-manifest.groovy"
  def pCommit = load ".jenkinsci/previous-commit.groovy"
  def parallelism = params.PARALLELISM
  def platform = sh(script: 'uname -m', returnStdout: true).trim()
  def previousCommit = pCommit.previousCommitOrCurrent()
  // params are always null unless job is started
  // this is the case for the FIRST build only.
  // So just set this to same value as default. 
  // This is a known bug. See https://issues.jenkins-ci.org/browse/JENKINS-41929
  if (parallelism == null) {
    parallelism = 4
  }
  if (env.NODE_NAME.contains('arm7')) {
    parallelism = 1
  }
  if ( env.NODE_NAME.contains('x86_64')) {
    parallelism = 8
  }
  // count docker image file in case there could be pre-build image saved in file

  def iC = dPullOrBuild.dockerPullOrUpdate("${platform}-develop-build",
                                           "${env.GIT_RAW_BASE_URL}/${env.GIT_COMMIT}/docker/develop/Dockerfile",
                                           "${env.GIT_RAW_BASE_URL}/${previousCommit}/docker/develop/Dockerfile",
                                           "${env.GIT_RAW_BASE_URL}/develop/docker/develop/Dockerfile",
                                           ['PARALLELISM': parallelism])
  if (GIT_LOCAL_BRANCH == 'develop' && manifest.manifestSupportEnabled()) {
    manifest.manifestCreate("${DOCKER_REGISTRY_BASENAME}:develop-build",
      ["${DOCKER_REGISTRY_BASENAME}:x86_64-develop-build",
       "${DOCKER_REGISTRY_BASENAME}:armv7l-develop-build",
       "${DOCKER_REGISTRY_BASENAME}:aarch64-develop-build"])
    manifest.manifestAnnotate("${DOCKER_REGISTRY_BASENAME}:develop-build",
      [
        [manifest: "${DOCKER_REGISTRY_BASENAME}:x86_64-develop-build",
         arch: 'amd64', os: 'linux', osfeatures: [], variant: ''],
        [manifest: "${DOCKER_REGISTRY_BASENAME}:armv7l-develop-build",
         arch: 'arm', os: 'linux', osfeatures: [], variant: 'v7'],
        [manifest: "${DOCKER_REGISTRY_BASENAME}:aarch64-develop-build",
         arch: 'arm64', os: 'linux', osfeatures: [], variant: '']
      ])
    withCredentials([usernamePassword(credentialsId: 'docker-hub-credentials', usernameVariable: 'login', passwordVariable: 'password')]) {
      manifest.manifestPush("${DOCKER_REGISTRY_BASENAME}:develop-build", login, password)
    }
  }

  iC.inside(""
  + " -e IROHA_POSTGRES_HOST=${env.IROHA_POSTGRES_HOST}"
  + " -e IROHA_POSTGRES_PORT=${env.IROHA_POSTGRES_PORT}"
  + " -e IROHA_POSTGRES_USER=${env.IROHA_POSTGRES_USER}"
  + " -e IROHA_POSTGRES_PASSWORD=${env.IROHA_POSTGRES_PASSWORD}"
  + " -v ${CCACHE_DIR}:${CCACHE_DIR}") {
        def scmVars = checkout scm
        def cmakeOptions = ""

        if ( coverageEnabled ) {
          cmakeOptions = " -DCOVERAGE=ON "
        }
        env.IROHA_VERSION = "0x${scmVars.GIT_COMMIT}"
        env.IROHA_HOME = "/opt/iroha"
        env.IROHA_BUILD = "${env.IROHA_HOME}/build"
        sh """
          ccache --version
          ccache --show-stats
          ccache --zero-stats
          ccache --max-size=5G
        """
        sh """
          cmake \
            -DTESTING=ON \
            -H. \
            -Bbuild \
            -DCMAKE_BUILD_TYPE=Debug \
            -DIROHA_VERSION=${env.IROHA_VERSION} \
            ${cmakeOptions}
        """
        sh "echo 1 > build/end.lock"
        sh "cmake --build build -- -j${parallelism}"
        sh "ccache --show-stats"
     }
}

def doPreCoverageStep() {
  waitUntil {
    sh(script: '[ -e build/end.lock ]', returnStatus: true)
    // if ( checkExistance == 0 ) { return true }
    // else { return false }
  }
  if ( env.NODE_NAME.contains('x86_64') ) {
    sh "docker load -i ${JENKINS_DOCKER_IMAGE_DIR}/${dockerImageFile}"
  }
  def iC = docker.image("${dockerAgentImage}")
  iC.inside(""
  + " -v ${CCACHE_DIR}:${CCACHE_DIR}") {
    sh "cmake --build build --target coverage.init.info"
  }
}

def doTestStep(testList) {
  if ( env.NODE_NAME.contains('x86_64') ) {
    sh "docker load -i ${JENKINS_DOCKER_IMAGE_DIR}/${dockerImageFile}"
  }

  def iC = docker.image("${dockerAgentImage}")
  sh "docker network create ${env.IROHA_NETWORK}"

  docker.image('postgres:9.5').withRun(""
    + " -e POSTGRES_USER=${env.IROHA_POSTGRES_USER}"
    + " -e POSTGRES_PASSWORD=${env.IROHA_POSTGRES_PASSWORD}"
    + " --name ${env.IROHA_POSTGRES_HOST}"
    + " --network=${env.IROHA_NETWORK}") {
      iC.inside(""
      + " -e IROHA_POSTGRES_HOST=${env.IROHA_POSTGRES_HOST}"
      + " -e IROHA_POSTGRES_PORT=${env.IROHA_POSTGRES_PORT}"
      + " -e IROHA_POSTGRES_USER=${env.IROHA_POSTGRES_USER}"
      + " -e IROHA_POSTGRES_PASSWORD=${env.IROHA_POSTGRES_PASSWORD}"
      + " --network=${env.IROHA_NETWORK}"
      + " -v ${CCACHE_DIR}:${CCACHE_DIR}"
      + " -v /tmp/${GIT_COMMIT}-${BUILD_NUMBER}:/tmp/${GIT_COMMIT}") {
        def testExitCode = sh(script: """cd build && ctest --output-on-failure -R '${testList}' """, returnStatus: true)
        if (testExitCode != 0) {
          currentBuild.result = "UNSTABLE"
        }
      }
    }
}

def doPostCoverageCoberturaStep() {
  if ( env.NODE_NAME.contains('x86_64') ) {
    sh "docker load -i ${JENKINS_DOCKER_IMAGE_DIR}/${dockerImageFile}"
  }
  def iC = docker.image("${dockerAgentImage}")

  iC.inside(""
  + " -v ${CCACHE_DIR}:${CCACHE_DIR}") {
    sh "cmake --build build --target coverage.info"
    sh "python /tmp/lcov_cobertura.py build/reports/coverage.info -o build/reports/coverage.xml"
    cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: '**/build/reports/coverage.xml', conditionalCoverageTargets: '75, 50, 0', failUnhealthy: false, failUnstable: false, lineCoverageTargets: '75, 50, 0', maxNumberOfBuilds: 50, methodCoverageTargets: '75, 50, 0', onlyStable: false, zoomCoverageChart: false
  }
}

def doPostCoverageSonarStep() {
  if ( env.NODE_NAME.contains('x86_64') ) {
    sh "docker load -i ${JENKINS_DOCKER_IMAGE_DIR}/${dockerImageFile}"
  }
  def iC = docker.image("${dockerAgentImage}")

  iC.inside(""
  + " -v ${CCACHE_DIR}:${CCACHE_DIR}") {
    sh "cmake --build build --target cppcheck"
    sh """
      sonar-scanner \
        -Dsonar.github.disableInlineComments \
        -Dsonar.github.repository='hyperledger/iroha' \
        -Dsonar.analysis.mode=preview \
        -Dsonar.login=${SONAR_TOKEN} \
        -Dsonar.projectVersion=${BUILD_TAG} \
        -Dsonar.github.oauth=${SORABOT_TOKEN} \
        -Dsonar.github.pullRequest=${CHANGE_ID}
    """
  }
}

return this
