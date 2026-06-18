#!/usr/bin/env bash
# =============================================================================
# TITAN Automation — GitHub Push Helper
# Usage:
#   bash scripts/github-push.sh "commit message"   ← custom message
#   bash scripts/github-push.sh                    ← auto-timestamped
# =============================================================================

set -euo pipefail
BOLD="\033[1m"; GREEN="\033[32m"; YELLOW="\033[33m"; RED="\033[31m"; CYAN="\033[36m"; RESET="\033[0m"

# Clean up stale git lock files left by crashed processes
for _lf in .git/index.lock .git/config.lock .git/MERGE_HEAD.lock .git/COMMIT_EDITMSG.lock .git/HEAD.lock; do
    [ -f "$_lf" ] && { rm -f "$_lf"; echo -e "${YELLOW}  ⚠ Removed stale $_lf${RESET}"; } || true
done

ok()   { echo -e "${GREEN}  ✔ $*${RESET}"; }
info() { echo -e "${CYAN}  → $*${RESET}"; }
err()  { echo -e "${RED}  ✖ $*${RESET}"; exit 1; }

echo -e "\n${BOLD}${CYAN}  TITAN — GitHub Push${RESET}\n"

# ── Commit message ────────────────────────────────────────────────────────────
if [[ -n "${1:-}" ]]; then
    MSG="$1"
else
    MSG="chore: auto-update $(date -u +'%Y-%m-%d %H:%M UTC')"
fi
info "Commit: $MSG"

# ── Check git ─────────────────────────────────────────────────────────────────
command -v git &>/dev/null || err "git not found"

# ── Inject GITHUB_TOKEN into remote URL if set ────────────────────────────────
REMOTE_URL=$(git remote get-url origin 2>/dev/null || echo "")
if [[ -z "$REMOTE_URL" ]]; then
    err "No git remote 'origin' set.\n  Fix: git remote add origin https://github.com/TITANICBHAI/titan-automation-framework.git"
fi

if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    # Inject token into https remote for authenticated push
    AUTH_URL=$(echo "$REMOTE_URL" | sed "s|https://|https://${GITHUB_TOKEN}@|")
    git remote set-url origin "$AUTH_URL"
    RESTORE_URL=true
fi

# ── Stage, commit, push ───────────────────────────────────────────────────────
info "Staging all changes..."
git add -A

if git diff --cached --quiet; then
    ok "Nothing to commit — working tree clean"
else
    git -c user.name="TITAN Bot" \
        -c user.email="titan@replit.local" \
        commit -m "$MSG"
    ok "Committed"

    info "Pushing to origin..."
    git push origin HEAD
    ok "Pushed successfully!"
fi

# ── Restore clean remote URL (hide token) ─────────────────────────────────────
if [[ "${RESTORE_URL:-false}" == true ]]; then
    git remote set-url origin "$REMOTE_URL"
fi

echo -e "\n${GREEN}${BOLD}  ✅  Done!${RESET}\n"
