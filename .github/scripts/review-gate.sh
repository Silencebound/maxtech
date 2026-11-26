#!/usr/bin/env bash
# Sets the `review-gate` commit status on a PR's head (owner decision 2026-10-19). Branch protection requires it, so
# GitHub's auto-merge only merges a PR whose risk tier allows it:
#
#   needs-owner label           failure  (the owner wants to look first)
#   no tier label               failure  (add trivial, normal or risky)
#   trivial or normal           success  (no review in that tier, docs/workflow.md)
#   risky                       success only when the repository owner's latest "Verdict:" review approves the head,
#                               or a commit whose only successors up to the head are merge commits (main merged in);
#                               "Request changes" fails; no verdict yet stays pending
#
# Reads PR data with gh and never checks out the PR's code, so it is safe under pull_request_target.
#   review-gate.sh <repo> <pr>     needs GH_TOKEN with statuses:write and pull-requests:read
set -euo pipefail

repo=$1
pr=$2
owner=${repo%%/*}

head=$(gh api "repos/$repo/pulls/$pr" --jq .head.sha)
labels=$(gh api "repos/$repo/pulls/$pr" --jq '[.labels[].name] | join(",")')

has() { case ",$labels," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

set_status() {
    echo "review-gate: $1 - $2"
    gh api -X POST "repos/$repo/statuses/$head" -f state="$1" -f context=review-gate -f description="$2" >/dev/null
}

if has needs-owner; then
    set_status failure "Waiting for the owner (needs-owner label)"
    exit 0
fi
if has trivial || has normal; then
    has risky || { set_status success "No review needed in this tier"; exit 0; }
fi
if ! has risky; then
    set_status failure "Add a risk label: trivial, normal or risky"
    exit 0
fi

# Latest verdict review by the repository owner (reviewers post as the owner's account).
verdict=$(gh api --paginate "repos/$repo/pulls/$pr/reviews" --jq \
    ".[] | select(.user.login == \"$owner\" and .state != \"DISMISSED\" and (.body | test(\"^Verdict:\"))) | [.submitted_at, .commit_id, (.body | split(\"\n\")[0])] | @tsv" \
    | sort | tail -n 1)
if [ -z "$verdict" ]; then
    set_status pending "Risky: waiting for a reviewer verdict"
    exit 0
fi
commit=$(cut -f2 <<<"$verdict")
line=$(cut -f3 <<<"$verdict")
case "$line" in
    "Verdict: Approve"*) ;;
    *)
        set_status failure "Reviewer: ${line#Verdict: }"
        exit 0
        ;;
esac
if [ "$commit" = "$head" ]; then
    set_status success "Approved at ${head:0:7}"
    exit 0
fi
# Approved an earlier commit: accept it when every PR commit after it is a merge commit (main merged in by pr.sh).
# The PR's own commit list leaves out main's commits that such a merge brings in (a compare would count them).
own=$(gh api --paginate "repos/$repo/pulls/$pr/commits" --jq '.[] | "\(.sha) \(.parents | length)"' \
    | awk -v c="$commit" 'found && $2 < 2 { n++ } $1 == c { found = 1 } END { print found ? n + 0 : -1 }')
if [ "$own" = 0 ]; then
    set_status success "Approved at ${commit:0:7}, main merged in since"
else
    set_status pending "Approval is for ${commit:0:7}; the head changed"
fi
