use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
pub struct MergeRequest {
    pub id: String,
    pub iid: i32,
    pub title: String,
    pub description: Option<String>,
    #[serde(rename = "source_branch")]
    pub source_branch: String,
    #[serde(rename = "target_branch")]
    pub target_branch: String,
    pub author: User,
    pub state: String,
    pub draft: bool,
    #[serde(default)]
    pub labels: Vec<String>,
    #[serde(default)]
    pub commits: Vec<Commit>,
    #[serde(default)]
    pub changes: Vec<FileChange>,
    #[serde(default)]
    pub approvals: Vec<Approval>,
    #[serde(default)]
    pub reviewers: Vec<User>,
}

#[derive(Deserialize)]
pub struct User {
    pub username: String,
    pub name: String,
    pub email: Option<String>,
}

#[derive(Deserialize)]
pub struct Commit {
    pub sha: String,
    pub message: String,
    pub author: CommitAuthor,
    pub timestamp: String,
}

#[derive(Deserialize)]
pub struct CommitAuthor {
    pub name: String,
    pub email: String,
}

#[derive(Deserialize)]
pub struct FileChange {
    pub path: String,
    #[serde(rename = "changeType")]
    pub change_type: String,
    #[serde(default)]
    pub additions: i32,
    #[serde(default)]
    pub deletions: i32,
}

#[derive(Deserialize)]
pub struct Approval {
    pub user: User,
    #[serde(rename = "approved_at")]
    pub approved_at: Option<String>,
}

#[derive(Serialize)]
pub struct Finding {
    pub severity: String,
    pub message: String,
    pub rule: String,
    pub location: Option<Location>,
    pub explanation: Option<String>,
    pub suggestion: Option<String>,
}

#[derive(Serialize)]
pub struct Location {
    pub file: Option<String>,
    pub line: Option<i32>,
}
