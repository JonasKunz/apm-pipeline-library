// Licensed to Elasticsearch B.V. under one or more contributor
// license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright
// ownership. Elasticsearch B.V. licenses this file to you under
// the Apache License, Version 2.0 (the "License"); you may
// not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import groovy.transform.Field

@Library('apm@current') _

// To store all the latest snapshot versions
@Field def latestVersions

pipeline {
  agent { label 'linux && immutable' }
  environment {
    REPO = 'observability-dev'
    HOME = "${env.WORKSPACE}"
    NOTIFY_TO = credentials('notify-to')
    PIPELINE_LOG_LEVEL='INFO'
  }
  options {
    timeout(time: 1, unit: 'HOURS')
    buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
    timestamps()
    ansiColor('xterm')
    disableResume()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    rateLimitBuilds(throttle: [count: 60, durationName: 'hour', userBoost: true])
    quietPeriod(10)
  }
  parameters {
    booleanParam(name: 'DRY_RUN_MODE', defaultValue: false, description: 'If true, allows to execute this pipeline in dry run mode, without sending a PR.')
  }
  stages {
    stage('Checkout') {
      steps {
        git(credentialsId: '2a9602aa-ab9f-4e52-baf3-b71ca88469c7-UserAndToken', url: "https://github.com/elastic/${REPO}.git")
      }
    }
    stage('Fetch latest versions') {
      steps {
        script {
          latestVersions = artifactsApi(action: 'latest-versions')
        }
        archiveArtifacts 'latest-versions.json'
      }
    }
    stage('Send Pull Request'){
      options {
        warnError('Pull Requests failed')
      }
      steps {
        generateSteps()
      }
    }
  }
  post {
    cleanup {
      notifyBuildResult()
    }
  }
}

def generateSteps(Map args = [:]) {
  def projects = readYaml(file: '.ci/.bump-stack-version.yml')
  def parallelTasks = [:]
  projects['projects'].each { project ->
    matrix( agent: 'linux && immutable',
            axes:[
              axis('REPO', [project.repo]),
              axis('BRANCH', project.branches),
              axis('ENABLED', [project.get('enabled', true)])
            ],
            excludes: [ axis('ENABLED', [ false ]) ]
    ) {
      bumpStackVersion(repo: env.REPO,
                       scriptFile: "${project.script}",
                       branch: env.BRANCH,
                       reusePullRequest: project.get('reusePullRequest', false),
                       labels: project.get('labels', ''))
    }
  }
}

def bumpStackVersion(Map args = [:]){
  def repo = args.containsKey('repo') ? args.get('repo') : error('bumpStackVersion: repo argument is required')
  def scriptFile = args.containsKey('scriptFile') ? args.get('scriptFile') : error('bumpStackVersion: scriptFile argument is required')
  def branch = args.containsKey('branch') ? args.get('branch') : error('bumpStackVersion: branch argument is required')
  def reusePullRequest = args.get('reusePullRequest', false)
  def labels = args.get('labels', '')
  log(level: 'INFO', text: "bumpStackVersion(repo: ${repo}, branch: ${branch}, scriptFile: ${scriptFile}, reusePullRequest: ${reusePullRequest}, labels: '${labels}')")

  def branchName = findBranch(branch: branch, versions: latestVersions)
  def versionEntry = latestVersions.get(branchName)
  def message = createPRDescription(versionEntry)
 
  catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
    deleteDir()
    setupAPMGitEmail(global: true)
    git(url: "https://github.com/elastic/${repo}.git", branch: branchName, credentialsId: '2a9602aa-ab9f-4e52-baf3-b71ca88469c7-UserAndToken')
    sh(script: "${scriptFile} '${versionEntry.build_id}'", label: "Prepare changes for ${repo}")

    pullRequest(reusePullRequest: reusePullRequest,
                stackVersion: versionEntry.build_id,
                message: message,
                labels: labels)
  }
}

def pullRequest(Map args = [:]){
  def stackVersion = args.stackVersion
  def message = args.message
  def labels = args.labels.replaceAll('\\s','')
  def reusePullRequest = args.get('reusePullRequest', false)
  if (labels.trim()) {
    labels = "automation,${labels}"
  }

  if (params.DRY_RUN_MODE) {
    log(level: 'INFO', text: "DRY-RUN: pullRequest(stackVersion: ${stackVersion}, reusePullRequest: ${reusePullRequest}, labels: ${labels}, message: '${message}')")
    return
  }

  if (reusePullRequest && ammendPullRequestIfPossible()) {
    log(level: 'INFO', text: 'Reuse existing Pull Request')
    return
  }
  githubCreatePullRequest(title: "bump: stack version '${stackVersion}'",
                          labels: "${labels}", description: "${message}")
}

def ammendPullRequestIfPossible() {
  log(level: 'INFO', text: 'TBD')
  return false
}

def findBranch(Map args = [:]){
  def branch = args.branch
  // special macro to look for the latest minor version
  if (branch.contains('<minor>')) {
    def parts = branch.split('\\.')
    def major = parts[0]
    branch = args.versions.collect{ k,v -> k }.findAll { it ==~ /${major}\.\d+/}.sort().last()
  }
  return branch
}

def createPRDescription(versionEntry) {
  return """
  ### What
  Bump stack version with the latest one.
  ### Further details
  ```
  ${versionEntry}
  ```
  """
}