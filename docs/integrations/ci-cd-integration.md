# CI/CD Integration Guide

Integrate Karandash into your CI/CD pipeline to automatically validate merge requests and pull requests across all major platforms.

## Overview

Karandash works with popular CI/CD platforms through:

- **GitHub Actions**: Composite action for easy setup
- **GitLab CI**: Job template with SAST integration
- **CircleCI**: Orb-ready configuration
- **Jenkins**: Declarative Jenkinsfile
- **Azure Pipelines**: Task-based pipeline configuration

Each platform integration includes:

- Automatic MR/PR validation
- SARIF code scanning integration
- PR/MR comment posting with results
- Environment variable support
- Custom configuration file paths
- Version pinning and auto-updates

## GitHub Actions

Use the official Karandash GitHub Action for the simplest setup.

### Prerequisites

1. **GitHub repository** with merge request/pull request workflows
2. **Java 17 or later** (automatically set up by the action)
3. **Karandash configuration** (`.karandash/config.yaml` in repository root, or custom path)
4. **Permissions** (if using Code Scanning or PR comments):
   - `contents: read` - Check out repository
   - `pull-requests: write` - Post PR comments
   - `security-events: write` - Upload SARIF results

### Step-by-Step Setup

1. Create workflow directory:
   ```bash
   mkdir -p .github/workflows
   ```

2. Create `.github/workflows/karandash.yml`:
   ```yaml
   name: Karandash MR Validation

   on:
     pull_request:
       types: [opened, synchronize, reopened]

   jobs:
     validate:
       runs-on: ubuntu-latest

       permissions:
         contents: read
         pull-requests: write
         security-events: write

       steps:
         - name: Checkout code
           uses: actions/checkout@v4
           with:
             fetch-depth: 0

         - name: Run Karandash validation
           uses: appKODE/karandash@v1.0.1
           with:
             sarif: 'true'
             comment: 'true'
   ```

3. Commit and push:
   ```bash
   git add .github/workflows/karandash.yml
   git commit -m "Add Karandash validation workflow"
   git push
   ```

4. The workflow runs automatically on all pull requests.

### Configuration Options

Customize the action with these inputs:

| Input | Description | Default | Example |
|-------|-------------|---------|---------|
| `version` | Release tag to download from GitHub Releases (checked against `SHA256SUMS`) | `latest` | `v1.0.0`, `latest` |
| `config-path` | Path to configuration file | Auto-discover | `.karandash/rules.yml` |
| `format` | Output format | `console` | `console`, `json`, `sarif` |
| `sarif` | Upload SARIF to Code Scanning | `false` | `true`, `false` |
| `comment` | Post results as PR comment | `false` | `true`, `false` |

### Advanced Example: Custom Configuration

```yaml
name: Karandash MR Validation

on:
  pull_request:
    types: [opened, synchronize, reopened]
  push:
    branches:
      - main
      - develop

jobs:
  validate:
    runs-on: ubuntu-latest

    permissions:
      contents: read
      pull-requests: write
      security-events: write

    steps:
      - name: Checkout code
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Run Karandash validation
        uses: appKODE/karandash@v1.0.1
        with:
          version: 'v1.0.1'  # Pin to specific version
          config-path: '.karandash/strict-rules.yml'
          format: 'sarif'
          sarif: 'true'
          comment: 'true'

      - name: Comment on PRs with results
        if: github.event_name == 'pull_request'
        run: |
          echo "Validation complete. Check details in the PR comment and Security tab."
```

### Outputs

The action provides these outputs for use in subsequent steps:

```yaml
steps:
  - name: Run Karandash validation
    id: validate
    uses: appKODE/karandash@v1.0.1
    with:
      comment: 'true'

  - name: Check validation result
    run: |
      echo "Passed: ${{ steps.validate.outputs.passed }}"
      echo "Findings: ${{ steps.validate.outputs.findings_count }}"
```

### Alternative: Manual JAR Download

If you prefer not to use the action, download and run the JAR directly:

```yaml
name: Karandash MR Validation (Manual JAR)

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  validate:
    runs-on: ubuntu-latest

    permissions:
      contents: read

    steps:
      - name: Checkout code
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Download Karandash
        run: |
          VERSION=$(curl -sL https://api.github.com/repos/appKODE/karandash/releases/latest | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
          curl -sLf -o karandash.jar "https://github.com/appKODE/karandash/releases/download/${VERSION}/karandash.jar"

      - name: Run validation
        run: |
          ./karandash validate --format sarif --output karandash-results.sarif
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

      - name: Upload SARIF results
        uses: github/codeql-action/upload-sarif@v3
        if: always()
        with:
          sarif_file: karandash-results.sarif
          category: karandash
```

### GitHub-Specific Tips

- **Code Scanning Integration**: Enable `sarif: 'true'` and check the Security > Code scanning tab
- **PR Comments**: Enable `comment: 'true'` to post results directly on PRs
- **Branch Protection**: Add this workflow as a required status check in branch protection rules
- **Skip on Draft PRs**: Use `if: !github.event.pull_request.draft`
- **Notification Badge**: Add workflow status badge to README:
  ```markdown
  ![Karandash](https://github.com/yourorg/repo/actions/workflows/karandash.yml/badge.svg)
  ```

---

## GitLab CI

Integrate Karandash into GitLab pipelines with SAST integration.

### Prerequisites

1. **GitLab repository** with CI/CD enabled
2. **Docker executor** for the `rinekri/karandash` image from Docker Hub, or a Java 17+ image for the JAR
3. **Karandash configuration** (`.karandash/config.yaml` in repository root)
4. **GitLab account** with merge request pipelines enabled

### Step-by-Step Setup

#### Option 1: Docker Image (Recommended)

1. Create or edit `.gitlab-ci.yml`:
   ```yaml
   karandash-validate:
     stage: test
     image:
       name: rinekri/karandash:v1.0.1      # native binary; rinekri/karandash:v1.0.1-jvm for the JVM build
       pull_policy: if-not-present

     rules:
       - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'

     script:
       - karandash validate --format sarif --output gl-sast-report.json

     artifacts:
       reports:
         sast: gl-sast-report.json
       expire_in: 1 week
   ```

#### Option 1b: Reusable Template

Include the template straight from GitHub at a release tag; its inputs cover the config path, output format,
stage and image (default `rinekri/karandash:v1.0.1`):

```yaml
include:
  - remote: 'https://raw.githubusercontent.com/appKODE/karandash/v1.0.1/.gitlab/templates/validate.yml'
    inputs:
      config_path: '.karandash/config.yaml'
      format: 'sarif'
```

#### Option 2: Download JAR

1. Create or edit `.gitlab-ci.yml`:
   ```yaml
   karandash-validate:
     stage: test
     image: eclipse-temurin:17-jre

     rules:
       - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'

     script:
       - wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
       - java -jar karandash.jar validate --format sarif --output gl-sast-report.json

     artifacts:
       reports:
         sast: gl-sast-report.json
       expire_in: 1 week
   ```

2. Commit and push:
   ```bash
   git add .gitlab-ci.yml
   git commit -m "Add Karandash validation job"
   git push
   ```

3. Validation runs automatically on all merge requests.

### Configuration Options

Customize the job with these settings:

```yaml
karandash-validate:
  stage: test
  image: eclipse-temurin:17-jre
  tags:
    - docker
    - linux

  rules:
    # Run only on MR pipelines
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'

  variables:
    KARANDASH_CONFIG: '.karandash/config.yaml'

  before_script:
    # Download karandash JAR
    - wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar

  script:
    - ./karandash validate --format sarif --output gl-sast-report.json

  artifacts:
    reports:
      sast: gl-sast-report.json
    expire_in: 1 week

  allow_failure: false
```

### Advanced Configuration

#### Run on Specific Branches Only

```yaml
rules:
  - if: '$CI_PIPELINE_SOURCE == "merge_request_event" && $CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "main"'
```

#### Use Custom Configuration File

```yaml
script:
  - ./karandash validate --config .gitlab/custom-rules.yml --format sarif --output gl-sast-report.json
```

#### Cache JAR Between Runs

```yaml
karandash-validate:
  stage: test
  image: eclipse-temurin:17-jre

  cache:
    key: karandash-jar
    paths:
      - karandash.jar
    policy: pull-push

  before_script:
    - |
      if [ ! -f karandash.jar ]; then
        wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
      fi

  script:
    - ./karandash validate --format sarif --output gl-sast-report.json

  artifacts:
    reports:
      sast: gl-sast-report.json
    expire_in: 1 week
```

#### Build from Source (If Karandash Is a Submodule)

```yaml
before_script:
  - ./gradlew :cli:installDist

script:
  - ./karandash/build/install/cli/bin/karandash validate --format sarif --output gl-sast-report.json
```

### GitLab-Specific Tips

- **SAST Integration**: The `sast` report artifact automatically integrates with GitLab's Security Dashboard
- **MR Widget**: SAST findings appear in the MR security widget
- **Merge Blocking**: Use `allow_failure: false` to block merges
- **Multiple Stages**: Run Karandash in parallel with other tests:
  ```yaml
  karandash-validate:
    needs: []  # No dependencies
  ```
- **Environment Variables**: Set custom env vars for rule evaluation:
  ```yaml
  variables:
    TEAM: 'backend'
    MIN_REVIEWERS: '2'
  ```

---

## CircleCI

Integrate Karandash into CircleCI workflows.

### Prerequisites

1. **CircleCI account** connected to your repository
2. **Java 17** (provided by Docker image)
3. **Karandash configuration** (`.karandash/config.yaml` in repository root)

### Step-by-Step Setup

1. Create `.circleci/config.yml`:
   ```yaml
   version: 2.1

   jobs:
     karandash-validate:
       docker:
         - image: cimg/openjdk:17.0
       steps:
         - checkout
         - run:
             name: Download Karandash
             command: |
               wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
         - run:
             name: Run Validation
             command: |
               ./karandash validate --format sarif --output karandash-results.sarif
         - store_artifacts:
             path: karandash-results.sarif
             destination: validation-results

   workflows:
     pr-validation:
       jobs:
         - karandash-validate:
             filters:
               branches:
                 only:
                   - /pull\/.*/
   ```

2. Commit and push:
   ```bash
   git add .circleci/config.yml
   git commit -m "Add Karandash validation job"
   git push
   ```

### Advanced Configuration

#### Run on All PR Events

```yaml
workflows:
  pr-validation:
    jobs:
      - karandash-validate:
          filters:
            branches:
              ignore: main
```

#### Multiple Validation Jobs

```yaml
jobs:
  karandash-validate:
    docker:
      - image: cimg/openjdk:17.0
    steps:
      - checkout
      - run:
          name: Download Karandash
          command: |
            wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
      - run:
          name: Run Validation
          command: |
            ./karandash validate --format sarif --output karandash-results.sarif
      - store_artifacts:
          path: karandash-results.sarif
          destination: validation-results

  unit-tests:
    docker:
      - image: cimg/openjdk:17.0
    steps:
      - checkout
      - run:
          name: Run Tests
          command: |
            ./gradlew test

workflows:
  pr-validation:
    jobs:
      - karandash-validate
      - unit-tests
```

#### Cache Karandash JAR

```yaml
karandash-validate:
  docker:
    - image: cimg/openjdk:17.0
  steps:
    - checkout
    - restore_cache:
        keys:
          - karandash-v1-{{ .Environment.CIRCLE_SHA1 }}
          - karandash-v1-
    - run:
        name: Download Karandash
        command: |
          if [ ! -f karandash.jar ]; then
            wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
          fi
    - save_cache:
        key: karandash-v1-{{ .Environment.CIRCLE_SHA1 }}
        paths:
          - karandash.jar
    - run:
        name: Run Validation
        command: |
          ./karandash validate --format sarif --output karandash-results.sarif
    - store_artifacts:
        path: karandash-results.sarif
        destination: validation-results
```

### CircleCI-Specific Tips

- **Artifacts**: SARIF results stored in artifacts for inspection
- **Parallel Jobs**: Run Karandash alongside other checks:
  ```yaml
  workflows:
    pr-validation:
      jobs:
        - karandash-validate
        - lint
        - test
  ```
- **Environment Variables**: Define in CircleCI project settings for rule evaluation
- **Failure Handling**: Jobs fail automatically if validation fails (exit code non-zero)

---

## Jenkins

Integrate Karandash into Jenkins declarative pipelines.

### Prerequisites

1. **Jenkins instance** with JVM access
2. **Java 17 or later** configured in Jenkins
3. **Git plugin** for repository checkout
4. **Karandash configuration** (`.karandash/config.yaml` in repository root)

### Step-by-Step Setup

1. Create `Jenkinsfile` in repository root:
   ```groovy
   pipeline {
       agent any

       tools {
           jdk 'jdk-17'
       }

       triggers {
           pullRequest()
       }

       stages {
           stage('Download Karandash') {
               steps {
                   sh '''
                       wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
                   '''
               }
           }

           stage('Validate MR') {
               steps {
                   sh '''
                       ./karandash validate --format sarif --output karandash-results.sarif
                   '''
               }
           }
       }

       post {
           always {
               archiveArtifacts artifacts: 'karandash-results.sarif', allowEmptyArchive: true
           }
           failure {
               echo 'Karandash validation failed!'
           }
       }
   }
   ```

2. Configure Jenkins job to use this Jenkinsfile:
   - Create a new Multibranch Pipeline job
   - Set repository source to your Git repository
   - Jenkins auto-discovers the Jenkinsfile

3. Validation runs automatically on all pull requests.

### Advanced Configuration

#### With Parallel Testing

```groovy
pipeline {
    agent any

    tools {
        jdk 'jdk-17'
    }

    triggers {
        pullRequest()
    }

    stages {
        stage('Setup') {
            steps {
                sh '''
                    wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
                '''
            }
        }

        stage('Parallel Checks') {
            parallel {
                stage('Karandash Validation') {
                    steps {
                        sh '''
                            ./karandash validate --format sarif --output karandash-results.sarif
                        '''
                    }
                }

                stage('Unit Tests') {
                    steps {
                        sh '''
                            ./gradlew test
                        '''
                    }
                }

                stage('Lint') {
                    steps {
                        sh '''
                            ./gradlew lint
                        '''
                    }
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'karandash-results.sarif', allowEmptyArchive: true
            junit '**/build/test-results/**/*.xml'
        }
        failure {
            echo 'Pipeline failed!'
        }
        success {
            echo 'All checks passed!'
        }
    }
}
```

#### With Custom Configuration

```groovy
stage('Validate MR') {
    steps {
        sh '''
            ./karandash validate \
              --config .jenkins/karandash-strict.yml \
              --format sarif \
              --output karandash-results.sarif
        '''
    }
}
```

#### With Conditional Stages

```groovy
stage('Karandash Validation') {
    when {
        changeRequest()  // Only on PR changes
    }
    steps {
        sh '''
            ./karandash validate --format sarif --output karandash-results.sarif
        '''
    }
}
```

#### With Artifact Fingerprinting

```groovy
post {
    always {
        archiveArtifacts artifacts: 'karandash-results.sarif', fingerprint: true
        recordIssues(
            enabledForFailure: true,
            tools: [
                sarif(pattern: 'karandash-results.sarif')
            ]
        )
    }
}
```

### Jenkins-Specific Tips

- **Tool Declarations**: Use `tools { jdk 'jdk-17' }` for automatic Java setup
- **Parallel Stages**: Run checks concurrently for faster feedback
- **Artifact Archiving**: SARIF results available in Jenkins UI
- **Pipeline Parameters**: Add configurable options:
  ```groovy
  parameters {
      string(name: 'CONFIG_PATH', defaultValue: '.karandash/config.yaml', description: 'Path to config')
      booleanParam(name: 'STRICT_MODE', defaultValue: false, description: 'Enable strict validation')
  }
  ```
- **Email Notifications**: Send results on failure:
  ```groovy
  post {
      failure {
          emailext(
              subject: 'Karandash validation failed',
              body: 'See Jenkins logs for details',
              to: 'team@example.com'
          )
      }
  }
  ```

---

## Azure Pipelines

Integrate Karandash into Azure Pipelines.

### Prerequisites

1. **Azure DevOps project** with repository
2. **Java 17** task available in Azure Pipelines
3. **Karandash configuration** (`.karandash/config.yaml` in repository root)
4. **Pull request trigger** enabled

### Step-by-Step Setup

1. Create `azure-pipelines.yml` in repository root:
   ```yaml
   trigger: none

   pr:
     - main
     - master
     - develop

   pool:
     vmImage: 'ubuntu-latest'

   steps:
   - task: JavaToolInstaller@0
     displayName: 'Set up JDK 17'
     inputs:
       versionSpec: '17'
       jdkArchitectureOption: 'x64'
       jdkSourceOption: 'PreInstalled'

   - script: |
       wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
     displayName: 'Download Karandash'

   - script: |
       ./karandash validate --format sarif --output karandash-results.sarif
     displayName: 'Run Validation'

   - task: PublishBuildArtifacts@1
     displayName: 'Publish SARIF Results'
     inputs:
       pathToPublish: 'karandash-results.sarif'
       artifactName: 'karandash-results'
     condition: always()
   ```

2. Push to repository:
   ```bash
   git add azure-pipelines.yml
   git commit -m "Add Karandash validation pipeline"
   git push
   ```

3. Configure in Azure DevOps:
   - Go to Pipelines > Create Pipeline
   - Select your repository
   - Choose "Existing Azure Pipelines YAML file"
   - Select `azure-pipelines.yml`

### Advanced Configuration

#### With Multiple Branches

```yaml
trigger:
  - main
  - develop

pr:
  - main
  - develop

strategy:
  matrix:
    Linux:
      imageName: 'ubuntu-latest'
    Windows:
      imageName: 'windows-latest'
    macOS:
      imageName: 'macOS-latest'

pool:
  vmImage: $(imageName)

steps:
  - task: JavaToolInstaller@0
    displayName: 'Set up JDK 17'
    inputs:
      versionSpec: '17'

  - script: |
      wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
    displayName: 'Download Karandash'
    condition: ne(variables['Agent.OS'], 'Windows_NT')

  - powershell: |
      Invoke-WebRequest -Uri "https://github.com/appKODE/karandash/releases/latest/download/karandash.jar" -OutFile "karandash.jar"
    displayName: 'Download Karandash (Windows)'
    condition: eq(variables['Agent.OS'], 'Windows_NT')

  - script: |
      ./karandash validate --format sarif --output karandash-results.sarif
    displayName: 'Run Validation'
```

#### With Custom Configuration

```yaml
variables:
  KARANDASH_CONFIG: '.azure/karandash-strict.yml'

steps:
  - script: |
      ./karandash validate \
        --config $(KARANDASH_CONFIG) \
        --format sarif \
        --output karandash-results.sarif
    displayName: 'Run Validation with Custom Config'
```

#### With Cache

```yaml
steps:
  - task: CacheBeta@0
    inputs:
      key: 'karandash | $(Agent.OS) | karandash.jar'
      path: 'karandash.jar'
      cacheHitVar: 'CACHE_RESTORED'
    displayName: 'Cache Karandash JAR'

  - script: |
      if [ "$(CACHE_RESTORED)" != "true" ]; then
        wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
      fi
    displayName: 'Download Karandash (if not cached)'

  - script: |
      ./karandash validate --format sarif --output karandash-results.sarif
    displayName: 'Run Validation'
```

#### With Test Publishing

```yaml
- task: PublishTestResults@2
  displayName: 'Publish Test Results'
  condition: always()
  inputs:
    testResultsFormat: 'JUnit'
    testResultsFiles: '**/build/test-results/**/*.xml'

- task: PublishCodeCoverageResults@1
  displayName: 'Publish Code Coverage'
  condition: always()
  inputs:
    codeCoverageTool: Cobertura
    summaryFileLocation: '**/coverage/cobertura-coverage.xml'
```

### Azure Pipelines-Specific Tips

- **PR Triggers**: Use `pr:` to trigger only on pull requests
- **Multiple Platforms**: Use `strategy.matrix` for Windows/Linux/macOS
- **Artifacts**: Results available in Azure Pipelines UI
- **Service Connections**: Use for accessing private registries:
  ```yaml
  - task: DownloadSecureFile@1
    inputs:
      secureFile: 'karandash-config'
  ```
- **Branch Protection**: Add pipeline as required check in branch policies

---

## Environment Variables

Configure these environment variables for platform-specific features.

### Required Tokens

| Variable | Platform | Purpose | Where to Set |
|----------|----------|---------|--------------|
| `GITHUB_TOKEN` | GitHub Actions | API access, comment posting | Automatically available |
| `GITLAB_TOKEN` | GitLab CI | API access | CI/CD Variables in project settings |
| `CIRCLE_TOKEN` | CircleCI | API access | Project settings > Environment Variables |
| `JENKINS_TOKEN` | Jenkins | API access | Jenkins Credentials |
| `SYSTEM_ACCESSTOKEN` | Azure Pipelines | API access | Automatically available |

### Configuration Variables

```bash
# Set via pipeline configuration or project settings

# Karandash behavior
export KARANDASH_CONFIG=.karandash/config.yaml
export KARANDASH_STRICT=true
export KARANDASH_LOG_LEVEL=INFO

# Platform-specific
export GITHUB_TOKEN=ghp_xxxxx
export GITLAB_TOKEN=glpat-xxxxx
export PR_NUMBER=123
export PR_TITLE="Feature: New validation rule"
export PR_AUTHOR=username
export BASE_REF=main
export HEAD_REF=feature-branch
```

### GitHub Actions Example

```yaml
env:
  KARANDASH_CONFIG: '.karandash/config.yaml'
  LOG_LEVEL: 'INFO'

jobs:
  validate:
    runs-on: ubuntu-latest
    env:
      PR_NUMBER: ${{ github.event.pull_request.number }}
      PR_TITLE: ${{ github.event.pull_request.title }}
      PR_AUTHOR: ${{ github.event.pull_request.user.login }}
```

### GitLab CI Example

```yaml
karandash-validate:
  variables:
    KARANDASH_CONFIG: '.karandash/config.yaml'
    GITLAB_TOKEN: $CI_JOB_TOKEN
    GITLAB_API_URL: $CI_API_V4_URL
  script:
    - ./karandash validate
```

### Jenkins Example

```groovy
environment {
    KARANDASH_CONFIG = '.karandash/config.yaml'
    GITHUB_TOKEN = credentials('github-token')
    LOG_LEVEL = 'INFO'
}
```

### Azure Pipelines Example

```yaml
variables:
  KARANDASH_CONFIG: '.karandash/config.yaml'
  LOG_LEVEL: 'INFO'
  SYSTEM_ACCESSTOKEN: $(System.AccessToken)

steps:
  - script: |
      ./karandash validate
    env:
      SYSTEM_ACCESSTOKEN: $(System.AccessToken)
```

---

## SARIF Integration

Enable code scanning integration to display findings in your platform's security dashboard.

### What Is SARIF?

SARIF (Static Analysis Results Interchange Format) is a standard JSON format for reporting analysis results. Karandash can output SARIF for integration with:

- GitHub Code Scanning
- GitLab SAST
- Azure DevOps Code Scanning
- Other SARIF-compatible tools

### Enabling SARIF Output

Add `--format sarif` to Karandash commands:

```bash
./karandash validate --format sarif --output results.sarif
```

### GitHub Code Scanning

Results appear in Security > Code scanning tab:

```yaml
- name: Run Karandash validation
  uses: appKODE/karandash@v1.0.1
  with:
    sarif: 'true'

- name: Upload SARIF results
  uses: github/codeql-action/upload-sarif@v3
  if: always()
  with:
    sarif_file: karandash-results.sarif
    category: karandash
```

#### View Results

1. Go to Security > Code scanning
2. Filter by "karandash" category
3. Click on findings for details
4. Click "Show more" to see remediation suggestions

### GitLab SAST Integration

Results appear in MR security widget and Security Dashboard:

```yaml
karandash-validate:
  script:
    - ./karandash validate --format sarif --output gl-sast-report.json

  artifacts:
    reports:
      sast: gl-sast-report.json
```

#### View Results

1. Open a merge request
2. Check the security widget in the MR overview
3. Go to Security > SAST to see all findings
4. Click on findings for detailed information

### Azure DevOps Code Scanning

Results available as published artifacts:

```yaml
- script: |
    ./karandash validate --format sarif --output karandash-results.sarif
  displayName: 'Run Validation'

- task: PublishBuildArtifacts@1
  displayName: 'Publish SARIF Results'
  inputs:
    pathToPublish: 'karandash-results.sarif'
    artifactName: 'karandash-results'
  condition: always()
```

### CircleCI with Artifacts

SARIF results stored and accessible in build artifacts:

```yaml
- run:
    name: Run Validation
    command: |
      ./karandash validate --format sarif --output karandash-results.sarif

- store_artifacts:
    path: karandash-results.sarif
    destination: validation-results
```

### Jenkins with Warnings Plugin

Archive and display SARIF results:

```groovy
post {
    always {
        recordIssues(
            enabledForFailure: true,
            tools: [
                sarif(pattern: 'karandash-results.sarif')
            ]
        )
    }
}
```

---

## Comment Posting

Automatically post validation results as PR/MR comments.

### GitHub Pull Request Comments

Enable in action configuration:

```yaml
- name: Run Karandash validation
  uses: appKODE/karandash@v1.0.1
  with:
    comment: 'true'
```

The action posts a comment with:
- Validation status (passed/failed)
- Number of findings
- Link to full results

#### Customize Comment (Advanced)

Use `actions/github-script` for custom comments:

```yaml
- name: Get validation results
  id: validate
  uses: appKODE/karandash@v1.0.1
  with:
    sarif: 'true'

- name: Post custom comment
  uses: actions/github-script@v7
  with:
    script: |
      const passed = '${{ steps.validate.outputs.passed }}' === 'true';
      const findings = parseInt('${{ steps.validate.outputs.findings_count }}');

      let message = '## Karandash Validation\n\n';
      message += passed ? '✅ **PASSED**' : '❌ **FAILED**';
      message += `\n\nFindings: ${findings}\n`;

      github.rest.issues.createComment({
        owner: context.repo.owner,
        repo: context.repo.repo,
        issue_number: context.issue.number,
        body: message
      });
```

### GitLab Merge Request Notes

Use GitLab API to post comments (manual implementation):

```yaml
- name: Post MR comment
  script:
    - |
      FINDINGS=$(./karandash validate --format json | grep -o '"count": [0-9]*')
      curl --request POST https://gitlab.com/api/v4/projects/$CI_PROJECT_ID/merge_requests/$CI_MERGE_REQUEST_IID/notes \
        --header "PRIVATE-TOKEN: $GITLAB_TOKEN" \
        --form "body=Karandash validation: $FINDINGS findings"
```

### Jenkins Job Description Updates

Update job description with results:

```groovy
stage('Update Results') {
    steps {
        script {
            def findings = sh(script: 'grep -o "finding" karandash-results.sarif | wc -l', returnStdout: true).trim()
            currentBuild.description = "Findings: ${findings}"
        }
    }
}
```

### Azure Pipelines with Task Groups

Create task group to post results:

```yaml
- task: GitHubComment@0
  displayName: 'Post GitHub Comment'
  condition: eq(variables['Build.Reason'], 'PullRequest')
  inputs:
    gitHubConnection: 'GitHub'
    repositoryName: '$(Build.Repository.Name)'
    issuenumber: '$(System.PullRequest.PullRequestNumber)'
    comment: |
      Karandash validation complete.
      Check the pipeline logs for details.
```

---

## Troubleshooting

### Common Issues and Solutions

#### Java Not Found

**Problem**: `java: command not found` or `No suitable Java version found`

**Solutions**:

1. **GitHub Actions**: Action includes Java setup, make sure no custom Java task conflicts
2. **GitLab CI**: Check image has Java: `docker run eclipse-temurin:17-jre java -version`
3. **CircleCI**: Use `cimg/openjdk:17.0` or similar
4. **Jenkins**: Configure Java in Global Tool Configuration > JDK
5. **Azure Pipelines**: Use `JavaToolInstaller` task

Verify Java version:
```bash
java -version  # Should show 17 or later
```

#### Karandash JAR Download Fails

**Problem**: `404 Not Found` or `Connection refused` when downloading JAR

**Solutions**:

1. **Check URL is correct**:
   ```bash
   curl -I https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
   ```

2. **Use specific version**:
   ```bash
   # Instead of 'latest', use explicit version
   wget https://github.com/appKODE/karandash/releases/download/v1.0.1/karandash.jar
   ```

3. **Check network access**:
   - Verify runner/agent can reach GitHub
   - Check firewall/proxy settings
   - Enable verbose logging: `wget -v ...`

#### Configuration File Not Found

**Problem**: `Error: .karandash/config.yaml not found`

**Solutions**:

1. **Verify file exists**:
   ```bash
   ls -la .karandash/config.yaml
   ```

2. **Use explicit config path**:
   ```bash
   ./karandash validate --config path/to/config.yml
   ```

3. **Check working directory**:
   - Make sure checkout step runs before validation
   - Verify working directory is repository root

#### PR Comment Not Posted

**Problem**: Validation runs but no PR comment appears

**Solutions**:

1. **GitHub Actions**: Check permissions:
   ```yaml
   permissions:
     pull-requests: write  # Required for comments
   ```

2. **GitLab CI**: Verify CI/CD token has API access:
   - Settings > CI/CD > Variables
   - Check `GITLAB_TOKEN` is set

3. **Verify correct PR context**:
   ```yaml
   # GitHub: Only on PR events
   if: github.event_name == 'pull_request'

   # GitLab: Only on MR pipelines
   rules:
     - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
   ```

#### SARIF Upload Fails

**Problem**: Error uploading SARIF to Code Scanning

**Solutions**:

1. **Check permissions** (GitHub):
   ```yaml
   permissions:
     security-events: write  # Required for SARIF upload
   ```

2. **Verify file exists**:
   ```bash
   ls -la karandash-results.sarif
   ```

3. **Check SARIF format**:
   ```bash
   # Should be valid JSON
   jq . karandash-results.sarif
   ```

4. **Use correct uploader**:
   - GitHub Actions: `github/codeql-action/upload-sarif@v3`
   - Azure: `PublishBuildArtifacts@1`
   - GitLab: `artifacts.reports.sast:`

#### Validation Takes Too Long

**Problem**: CI/CD pipeline timeout due to slow validation

**Solutions**:

1. **Cache JAR**:
   - GitHub Actions: Built-in caching
   - GitLab CI: Use `cache:` section
   - CircleCI: Use `save_cache` / `restore_cache`
   - Azure: Use `CacheBeta@0`

2. **Parallel execution**:
   - Run Karandash alongside other checks
   - Don't run sequentially with other jobs

3. **Simplify configuration**:
   - Disable unused rules
   - Reduce rule complexity
   - Use targeted rule sets per branch

#### Exit Code Not Respected

**Problem**: Pipeline continues even when validation fails

**Solutions**:

1. **Make sure non-zero exit on failure**:
   ```bash
   ./karandash validate || exit 1
   ```

2. **Check allow_failure** (GitLab):
   ```yaml
   allow_failure: false  # Required to fail job
   ```

3. **Check Jenkins failure handling**:
   ```groovy
   script {
       def status = sh(script: './karandash validate', returnStatus: true)
       if (status != 0) {
           error("Validation failed")
       }
   }
   ```

### Debug Logging

Enable verbose output for troubleshooting:

```bash
# GitHub Actions
./karandash --debug validate

# All platforms
export KARANDASH_LOG_LEVEL=DEBUG
./karandash validate
```

### Useful Commands

```bash
# Verify config file
./karandash validate --config .karandash/config.yaml --dry-run

# Check SARIF output
jq . karandash-results.sarif | head -20

# Test download URL
curl -I https://github.com/appKODE/karandash/releases/latest/download/karandash.jar

# Verify Java version
java -version

# Check JAR integrity
jar tf karandash.jar | head -10
```

### Getting Help

If issues persist:

1. **Check logs**: Full pipeline/workflow logs in CI/CD UI
2. **Enable debug mode**: Add `--debug` flag to Karandash
3. **Verify configuration**: Test locally with same config
4. **Report issue**: File issue at [GitHub Issues](https://github.com/appKODE/karandash/issues)
   - Include platform, version, configuration
   - Attach sanitized logs

---

## Next Steps

After integrating Karandash into your CI/CD pipeline:

1. **Configure validation rules**: See [Rules Reference](../rules/index.md)
2. **Create custom rules**: See [Creating Plugins](../plugins/creating-plugins.md)
3. **Monitor findings**: Review findings in your platform's security dashboard
4. **Iterate on rules**: Refine rules based on findings and team feedback
5. **Scale across projects**: Apply same configuration to multiple repositories

## Summary

Karandash works with all major CI/CD platforms:

| Platform | Integration Type | Best For |
|----------|-----------------|----------|
| **GitHub Actions** | Native action | GitHub-native teams, simplest setup |
| **GitLab CI** | CI/CD job | GitLab-native teams, SAST integration |
| **CircleCI** | Workflow job | CircleCI users, artifact storage |
| **Jenkins** | Declarative pipeline | Enterprise Jenkins environments |
| **Azure Pipelines** | Pipeline task | Azure DevOps teams, multi-platform |

Choose the platform that best fits your development workflow and start validating merge requests automatically.
