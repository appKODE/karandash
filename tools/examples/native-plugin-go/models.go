package main

type MergeRequest struct {
	ID           string       `json:"id"`
	IID          int          `json:"iid"`
	Title        string       `json:"title"`
	Description  *string      `json:"description"`
	SourceBranch string       `json:"source_branch"`
	TargetBranch string       `json:"target_branch"`
	Author       User         `json:"author"`
	State        string       `json:"state"`
	Draft        bool         `json:"draft"`
	Labels       []string     `json:"labels"`
	Commits      []Commit     `json:"commits"`
	Changes      []FileChange `json:"changes"`
	Approvals    []Approval   `json:"approvals"`
	Reviewers    []User       `json:"reviewers"`
}

type User struct {
	Username string  `json:"username"`
	Name     string  `json:"name"`
	Email    *string `json:"email,omitempty"`
}

type Commit struct {
	SHA       string       `json:"sha"`
	Message   string       `json:"message"`
	Author    CommitAuthor `json:"author"`
	Timestamp string       `json:"timestamp"`
}

type CommitAuthor struct {
	Name  string `json:"name"`
	Email string `json:"email"`
}

type FileChange struct {
	Path       string `json:"path"`
	ChangeType string `json:"changeType"`
	Additions  int    `json:"additions"`
	Deletions  int    `json:"deletions"`
}

type Approval struct {
	User       User    `json:"user"`
	ApprovedAt *string `json:"approved_at,omitempty"`
}

type Finding struct {
	Severity    string    `json:"severity"`
	Message     string    `json:"message"`
	Rule        string    `json:"rule"`
	Location    *Location `json:"location,omitempty"`
	Explanation *string   `json:"explanation,omitempty"`
	Suggestion  *string   `json:"suggestion,omitempty"`
}

type Location struct {
	File *string `json:"file,omitempty"`
	Line *int    `json:"line,omitempty"`
}
