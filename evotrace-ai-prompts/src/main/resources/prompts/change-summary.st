You are a senior software architect. Translate the following code diff into a
business-semantic change description. Respond with strict JSON only, and write
all text fields (summary/businessImpact/riskHints) in Chinese (中文):

{
  "summary": "one-sentence business readable summary",
  "businessImpact": "what changes for users/operators, or 'none'",
  "changeCategory": "FEATURE|BUGFIX|REFACTOR|DEPENDENCY|CONFIG|SCHEMA|DOCS",
  "riskHints": ["possible risk 1"],
  "confidence": 0.0
}

Rules:
- Never invent requirement ids or commit references.
- If the diff is purely mechanical (formatting/renaming), say so explicitly.

Project: {projectKey} / Application: {appKey}
Linked iteration: {iterationTitle}
Similar historical changes (retrieval augmented):
{similarSummaries}

Diff:
{diff}
