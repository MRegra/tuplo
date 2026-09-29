#!/usr/bin/env bash
# Regression test for scripts/check-no-draft-posts.sh.
#
# Builds a series of throwaway git repositories under a temp directory, copies the
# script under test into each one, and asserts its exit code (and, where noted, its
# stderr) for every case the check is supposed to handle. Uses only bash, git and
# coreutils; touches nothing outside its own temp directories.
#
# Usage: bash scripts/test-check-no-draft-posts.sh
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script_under_test="$here/check-no-draft-posts.sh"

tmp_root=$(mktemp -d) || { echo "test-check-no-draft-posts: mktemp failed" >&2; exit 2; }
trap 'rm -rf "$tmp_root"' EXIT

failures=0
checked=0

# git commands in the throwaway repos are always run with an explicit identity so the
# test never depends on (or touches) the caller's global git config.
git_id=(-c user.name="check-no-draft-posts test" -c user.email="test@example.invalid")

new_repo() {
  local dir="$1"
  mkdir -p "$dir"
  git "${git_id[@]}" -C "$dir" init -q -b main
  cp "$script_under_test" "$dir/check-no-draft-posts.sh"
}

# assert_case NAME EXPECTED_RC DIR [EXPECTED_STDERR_SUBSTRING]
assert_case() {
  local name="$1" expected_rc="$2" dir="$3" expect_substr="${4:-}"
  checked=$((checked + 1))
  local out rc
  out=$(cd "$dir" && bash check-no-draft-posts.sh 2>&1)
  rc=$?
  if [ "$rc" -ne "$expected_rc" ]; then
    echo "FAIL: $name: expected exit $expected_rc, got $rc" >&2
    echo "  output: $out" >&2
    failures=$((failures + 1))
    return
  fi
  if [ -n "$expect_substr" ] && [[ "$out" != *"$expect_substr"* ]]; then
    echo "FAIL: $name: expected output to mention '$expect_substr'" >&2
    echo "  output: $out" >&2
    failures=$((failures + 1))
    return
  fi
  echo "PASS: $name"
}

# 1. Clean tree -> 0
d=$tmp_root/clean
new_repo "$d"
{
  echo "# Hello"
  echo "Nothing here."
} > "$d/README.md"
git "${git_id[@]}" -C "$d" add -A
git "${git_id[@]}" -C "$d" commit -q -m init
assert_case "clean tree" 0 "$d"

# 2. Staged draft.md with status: draft -> 1, stderr names the file
d=$tmp_root/staged-draft
new_repo "$d"
mkdir -p "$d/docs"
{
  echo "---"
  echo "status: draft"
  echo "---"
  echo "draft body"
} > "$d/docs/foo.md"
git "${git_id[@]}" -C "$d" add docs/foo.md check-no-draft-posts.sh
assert_case "staged draft" 1 "$d" "docs/foo.md"

# 3. Untracked, not-ignored draft -> 1
d=$tmp_root/untracked-draft
new_repo "$d"
git "${git_id[@]}" -C "$d" commit -q --allow-empty -m init
{
  echo "status: draft"
} > "$d/untracked.md"
assert_case "untracked not-ignored draft" 1 "$d" "untracked.md"

# 4. status: "draft" (quoted) -> 1
d=$tmp_root/quoted-draft
new_repo "$d"
git "${git_id[@]}" -C "$d" commit -q --allow-empty -m init
{
  echo 'status: "draft"'
} > "$d/quoted.md"
assert_case 'status: "draft" (quoted)' 1 "$d" "quoted.md"

# 5. Status: Draft (different case) -> 1
d=$tmp_root/case-draft
new_repo "$d"
git "${git_id[@]}" -C "$d" commit -q --allow-empty -m init
{
  echo "Status: Draft"
} > "$d/cased.md"
assert_case "Status: Draft (case-insensitive)" 1 "$d" "cased.md"

# 6. A draft ignored by docs/posts/* -> 0
d=$tmp_root/ignored-draft
new_repo "$d"
{
  echo "docs/posts/*"
  echo "!docs/posts/README.md"
} > "$d/.gitignore"
mkdir -p "$d/docs/posts"
echo "# Posts" > "$d/docs/posts/README.md"
{
  echo "status: draft"
} > "$d/docs/posts/0002-local-only.md"
git "${git_id[@]}" -C "$d" add .gitignore docs/posts/README.md check-no-draft-posts.sh
git "${git_id[@]}" -C "$d" commit -q -m init
assert_case "draft ignored via docs/posts/*" 0 "$d"

# 7. pending_removal path -> 0, with a WARNING on stderr
d=$tmp_root/pending-removal
new_repo "$d"
mkdir -p "$d/docs/posts"
{
  echo "status: draft"
} > "$d/docs/posts/0001-the-shared-bag-of-notes.md"
git "${git_id[@]}" -C "$d" add docs/posts/0001-the-shared-bag-of-notes.md check-no-draft-posts.sh
git "${git_id[@]}" -C "$d" commit -q -m init
assert_case "pending_removal warns but passes" 0 "$d" "WARNING"

# 8. status: published -> 0
d=$tmp_root/published
new_repo "$d"
git "${git_id[@]}" -C "$d" commit -q --allow-empty -m init
{
  echo "status: published"
} > "$d/pub.md"
assert_case "status: published" 0 "$d"

# 9. status: drafted (not an exact match) -> 0
d=$tmp_root/drafted
new_repo "$d"
git "${git_id[@]}" -C "$d" commit -q --allow-empty -m init
{
  echo "status: drafted"
} > "$d/drafted.md"
assert_case "status: drafted (not draft)" 0 "$d"

# 10. Outside a git repo -> 2
d=$tmp_root/no-git
mkdir -p "$d"
cp "$script_under_test" "$d/check-no-draft-posts.sh"
assert_case "outside a git repo" 2 "$d"

echo
echo "$checked cases checked, $failures failed"
if [ "$failures" -gt 0 ]; then
  exit 1
fi
