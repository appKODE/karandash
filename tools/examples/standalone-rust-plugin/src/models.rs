use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize)]
pub struct MergeRequest {
    pub title: String,
    pub description: Option<String>,
    pub source_branch: String,
    pub target_branch: String,
    pub author: User,
    pub state: String,
    #[serde(default)]
    pub draft: bool,
    #[serde(default)]
    pub labels: Vec<String>,
    #[serde(default)]
    pub commits: Vec<Commit>,
    #[serde(default)]
    pub changes: Vec<FileChange>,
}

#[derive(Debug, Deserialize)]
pub struct User {
    pub username: String,
    pub name: String,
    pub email: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct Commit {
    pub sha: String,
    pub message: String,
    pub author: CommitAuthor,
    pub timestamp: String,
}

#[derive(Debug, Deserialize)]
pub struct CommitAuthor {
    pub name: String,
    pub email: String,
}

#[derive(Debug, Deserialize)]
pub struct FileChange {
    pub path: String,
    #[serde(rename = "changeType")]
    pub change_type: String,
    #[serde(default)]
    pub additions: i32,
    #[serde(default)]
    pub deletions: i32,
}

#[derive(Debug, Serialize)]
pub struct Finding {
    pub severity: String,
    pub message: String,
    pub rule: String,
    pub location: Option<Location>,
    pub explanation: Option<String>,
    pub suggestion: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct Location {
    pub file: Option<String>,
    pub line: Option<i32>,
}
