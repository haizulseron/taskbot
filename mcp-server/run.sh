#!/usr/bin/env bash
# Launcher for the taskbot MCP server.
#
# Two modes, controlled by MCP_TRANSPORT:
#
#   stdio (default)  — for SSH-tunneled clients:
#       ssh telegrambot@vm /path/to/run.sh
#
#   http             — for remote HTTPS clients (via Cloudflare Tunnel):
#       MCP_TRANSPORT=http /path/to/run.sh
#       Requires MCP_AUTH_TOKEN to be set.
#
# stderr is redirected to a per-day log so we can debug crashes without
# polluting the SSH stdio pipe (which carries MCP messages in stdio mode).

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${DIR}/logs"
mkdir -p "${LOG_DIR}"
LOG_FILE="${LOG_DIR}/server-$(date +%Y%m%d).log"

# Auto-load auth token from file if not already in env (HTTP mode only)
if [ "${MCP_TRANSPORT:-stdio}" = "http" ] && [ -z "${MCP_AUTH_TOKEN:-}" ] && [ -f "${DIR}/auth-token.txt" ]; then
    export MCP_AUTH_TOKEN="$(cat "${DIR}/auth-token.txt")"
fi

# stderr → log file, stdout stays available for stdio MCP protocol
exec 2>>"${LOG_FILE}"
echo "[$(date -Iseconds)] starting MCP server (transport=${MCP_TRANSPORT:-stdio})" >&2

exec "${DIR}/.venv/bin/python" "${DIR}/server.py"
