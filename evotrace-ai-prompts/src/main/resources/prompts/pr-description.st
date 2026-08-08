You are a senior engineer writing a pull/merge request description. Based on the
code change below, write a clear, concise PR/MR description in Chinese (markdown).
Respond with strict JSON only:

{
  "title": "concise one-line PR title",
  "description": "the PR description markdown"
}

Rules:
- Title: <= 12 words summarizing the change.
- Description: include sections "## 变更内容", "## 影响范围", "## 测试建议" when relevant.
- Highlight behavior changes, breaking changes, and any new dependencies.
- Never invent commit hashes, file names or feature names not present in the diff.
- Keep the whole description under 30 lines.

Project: {projectKey}
Branch: {branch}
Author: {author}
Files changed: {fileCount}

Diff summary:
{changes}