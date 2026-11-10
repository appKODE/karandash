// AWS CodeCommit karandash configuration
//
// Authentication options:
// - Explicit static credentials (accessKeyId + secretAccessKey) with SigV4 signing
// - Environment credentials (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)
// - IAM role/OIDC credentials available to the execution environment
//
// Recommended in CI: short-lived IAM role credentials.
// Static keys should be used only when role-based auth is not possible.
//
// The provider uses AWS CodeCommit endpoint:
// https://codecommit.<region>.amazonaws.com/
//
// It requires:
// - region
// - read access to pull request, commits, and differences APIs
//
// Typical runtime sources:
// - EC2 instance IAM roles (recommended)
// - ECS task IAM roles
// - AWS credentials file (~/.aws/credentials)
// - Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)

platforms {
    awsCodeCommit {
        region = "us-east-1"
        // Optional explicit credentials:
        // accessKeyId = env.AWS_ACCESS_KEY_ID
        // secretAccessKey = env.AWS_SECRET_ACCESS_KEY
    }
}

rules {
    titleFormat {
        pattern = "^(feat|fix|docs|test|refactor|chore|ci|perf|style|build)(\\(.+\\))?: .+"
        maxLength = 72
        suggestion = "Use conventional commit format, e.g. 'feat: add S3 bucket policy' or 'fix: resolve IAM permission error'"
        explanation = "Conventional commit prefixes enable automated changelogs and semantic versioning"
    }

    commitMessages {
        pattern = "^(feat|fix|docs|test|refactor|chore|ci|perf|style|build)(\\(.+\\))?: .+"
        maxLength = 100
        suggestion = "Format commits as '<type>[(scope)]: <description>', e.g. 'chore(deps): update AWS SDK to v3'"
        explanation = "Consistent commit messages improve traceability across CodePipeline and CodeBuild runs"
    }

    approval {
        minApprovals = 2
    }

    conflict {
        blockOnConflicts = "fail"
    }

    sizeLimits {
        maxLines = 1000
        maxFiles = 50
    }
}

reporters {
    console = true
}
