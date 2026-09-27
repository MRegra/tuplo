#!/usr/bin/env bash
# Fails if any Markdown file that would be published with this repo is marked as a draft
# (a front-matter line `status: draft`). Drafts belong in the owner's private writings area
# or in the gitignored docs/posts/ folder -- the public repo only links to published posts.
#
# Scans tracked files plus untracked files that are not ignored, so it also catches a draft
# that is about to be `git add`-ed. Ignored files (local drafts) are skipped on purpose.
#
# Exit codes: 0 = clean, 1 = draft found, 2 = the check itself could not run.
set -uo pipefail

# Drafts that were committed before this check existed and are waiting for the owner to
# remove them (issue #3). They only produce a warning. Delete an entry together with its
# file. Do NOT add new entries: a new draft goes to the gitignored docs/posts/ folder.
pending_removal=(
  "docs/posts/0001-the-shared-bag-of-notes.md"
)

root=$(git rev-parse --show-toplevel) || { echo "check-no-draft-posts: not a git repository" >&2; exit 2; }
cd "$root" || exit 2

files=$(git grep --untracked -l -I -i -E \
  '^status:[[:space:]]*["'\'']?draft["'\'']?[[:space:]]*$' \
  -- '*.md' '*.markdown' '*.mdx')
rc=$?

if [ "$rc" -gt 1 ]; then
  echo "check-no-draft-posts: git grep failed (exit $rc)" >&2
  exit 2
fi

drafts=()
while IFS= read -r file; do
  [ -n "$file" ] || continue
  pending=false
  for p in "${pending_removal[@]}"; do
    [ "$file" = "$p" ] && pending=true
  done
  if $pending; then
    echo "WARNING: $file is a draft pending removal from the public repo (issue #3)." >&2
  else
    drafts+=("$file")
  fi
done <<< "$files"

if [ "${#drafts[@]}" -gt 0 ]; then
  echo "ERROR: draft posts found in a published path:" >&2
  printf '  %s\n' "${drafts[@]}" >&2
  echo "Move drafts to your private writings area (or the gitignored docs/posts/ folder) and" >&2
  echo "link only to the published post from docs/posts/README.md." >&2
  exit 1
fi

echo "OK: no draft posts in published paths."
