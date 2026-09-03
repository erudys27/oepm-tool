#!/usr/bin/env bash
# One-time setup: puts this directory (cli/) on PATH so the global `oepm`
# command works from inside any oepm-managed project, without a
# ./oepm.bat prefix and without touching each project individually. Safe
# to re-run - does nothing if already set up. See README.md's
# "Per-machine setup" for why this is a *separate* script from the
# per-project oepm/oepm.bat, not the same thing installed differently.
set -e

CLI_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MARKER="# added by oepm-tool's cli/install.sh"

pick_rc_file() {
    case "$SHELL" in
        */zsh) echo "$HOME/.zshrc" ;;
        */bash) echo "$HOME/.bashrc" ;;
        *) echo "$HOME/.profile" ;;
    esac
}

RC_FILE="$(pick_rc_file)"

if [ -f "$RC_FILE" ] && grep -qF "$CLI_DIR" "$RC_FILE"; then
    echo "Already set up - $CLI_DIR is already added to PATH in $RC_FILE."
    exit 0
fi

{
    echo ""
    echo "$MARKER"
    echo "export PATH=\"$CLI_DIR:\$PATH\""
} >> "$RC_FILE"

echo "Added $CLI_DIR to PATH via $RC_FILE."
echo "Open a new terminal (or run: source \"$RC_FILE\") for it to take effect."
