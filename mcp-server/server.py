#!/usr/bin/env python3
"""
Taskbot MCP server — exposes live runtime state of the Telegram task-bot
to remote Claude clients (e.g. Claude Desktop on the user's Windows machine).

Designed to run on the same VM as the bot, over stdio. The Claude client
launches this server via `ssh telegrambot@vm /path/to/run.sh`, and SSH
carries the stdio MCP messages.

Tools exposed:
  • bot_health           — supervisor status + uptime + Google online flag
  • tail_err_log         — recent stderr lines
  • tail_out_log         — recent stdout lines
  • recent_actions       — full detail of last N action_log entries
  • query_action_log     — aggregate stats over a time window
  • recent_conversation  — last N user/assistant turns
  • recent_commits       — git log, last N hours
  • db_query             — read-only SELECT escape hatch
  • restart_bot          — supervisorctl restart taskbot (uses NOPASSWD)
"""

from __future__ import annotations

import os
import re
import secrets
import sqlite3
import subprocess
import sys
from pathlib import Path

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings


# ── Paths (resolved once at startup) ────────────────────────────────────────
PROJECT_ROOT = Path("/home/telegrambot/projects/taskbot")
DB_PATH      = PROJECT_ROOT / "data" / "taskbot.db"
ERR_LOG      = PROJECT_ROOT / "logs" / "taskbot.err.log"
OUT_LOG      = PROJECT_ROOT / "logs" / "taskbot.out.log"

# Hard caps so a misuse can't tar-pit the SSH pipe with megabytes of text
MAX_LOG_LINES   = 1000
MAX_LOG_BYTES   = 100_000   # ~100 KB
MAX_DB_ROWS     = 500
MAX_SQL_LENGTH  = 2000


# DNS rebinding protection — FastMCP rejects HTTP requests whose Host header
# isn't in this list. Cloudflare Tunnel forwards requests with the public
# hostname, so we need to allow it explicitly. Extra hostnames can be added
# via MCP_ALLOWED_HOSTS (comma-separated) without rebuilding.
_default_allowed_hosts = [
    "taskbot-mcp.qinrealtor2001.org",
    "localhost",
    "127.0.0.1",
    "localhost:8765",
    "127.0.0.1:8765",
]
_extra_hosts = [h.strip() for h in os.environ.get("MCP_ALLOWED_HOSTS", "").split(",") if h.strip()]
_allowed_hosts = _default_allowed_hosts + _extra_hosts

mcp = FastMCP(
    "taskbot",
    transport_security=TransportSecuritySettings(
        enable_dns_rebinding_protection=True,
        allowed_hosts=_allowed_hosts,
        # Origins matter for CORS pre-flight from browsers; Claude Desktop's
        # connector backend doesn't send Origin, but we add Cloudflare's just in case.
        allowed_origins=[
            "https://taskbot-mcp.qinrealtor2001.org",
            "https://claude.ai",
        ],
    ),
)


# ── Helpers ─────────────────────────────────────────────────────────────────

def _tail_file(path: Path, lines: int) -> str:
    """Return the last `lines` lines of a file, capped at MAX_LOG_BYTES."""
    if not path.exists():
        return f"(file not found: {path})"
    n = max(1, min(lines, MAX_LOG_LINES))
    # Read whole file then slice — files here are typically <50MB and we only
    # do this on demand. If they ever grow, switch to seek-from-end.
    try:
        text = path.read_text(errors="replace")
    except Exception as e:
        return f"(read failed: {e})"
    all_lines = text.splitlines()
    tail = "\n".join(all_lines[-n:])
    if len(tail) > MAX_LOG_BYTES:
        tail = tail[-MAX_LOG_BYTES:]
        tail = "...[truncated to last %d bytes]...\n%s" % (MAX_LOG_BYTES, tail)
    return tail or "(empty)"


def _connect_db() -> sqlite3.Connection:
    if not DB_PATH.exists():
        raise FileNotFoundError(f"Database missing: {DB_PATH}")
    # Open read-only via URI to enforce no-writes from this process
    uri = f"file:{DB_PATH}?mode=ro"
    conn = sqlite3.connect(uri, uri=True, timeout=5.0)
    conn.row_factory = sqlite3.Row
    return conn


def _format_rows(rows: list[sqlite3.Row], limit: int = MAX_DB_ROWS) -> str:
    if not rows:
        return "(no rows)"
    capped = rows[:limit]
    truncated = len(rows) > limit
    cols = capped[0].keys()
    out = ["\t".join(cols)]
    for r in capped:
        out.append("\t".join("" if r[c] is None else str(r[c]) for c in cols))
    if truncated:
        out.append(f"... ({len(rows) - limit} more rows truncated)")
    return "\n".join(out)


# ── Tools ───────────────────────────────────────────────────────────────────

@mcp.tool()
def bot_health() -> str:
    """Snapshot of bot health: supervisor status, uptime, Google online state,
    and a count of distinct error patterns in the last 24h. Quick triage view."""
    parts: list[str] = []

    # Supervisor status
    try:
        r = subprocess.run(
            ["sudo", "-n", "/usr/bin/supervisorctl", "status", "taskbot"],
            capture_output=True, text=True, timeout=5,
        )
        parts.append(f"supervisor: {r.stdout.strip() or r.stderr.strip()}")
    except Exception as e:
        parts.append(f"supervisor: error ({e})")

    # Last 100 err lines — count distinct error categories
    if ERR_LOG.exists():
        try:
            tail = "\n".join(ERR_LOG.read_text(errors="replace").splitlines()[-500:])
        except Exception as e:
            tail = ""
            parts.append(f"err log read failed: {e}")
        invalid_grant = tail.count("invalid_grant")
        parts.append(f"err log (last 500 lines): {len(tail.splitlines())} lines, "
                     f"{invalid_grant} invalid_grant occurrences")

    # Action log: last-24h success/error counts
    try:
        with _connect_db() as conn:
            cur = conn.execute(
                "SELECT status, COUNT(*) c FROM action_log "
                "WHERE ts > strftime('%s','now') - 86400 GROUP BY status"
            )
            counts = {row["status"]: row["c"] for row in cur}
            parts.append(f"action_log (24h): {counts.get('success', 0)} ok / "
                         f"{counts.get('error', 0)} err")

            # Most recent error category if any
            cur = conn.execute(
                "SELECT error_category, COUNT(*) c FROM action_log "
                "WHERE status='error' AND ts > strftime('%s','now') - 86400 "
                "GROUP BY error_category ORDER BY c DESC LIMIT 3"
            )
            top_errs = [(r["error_category"], r["c"]) for r in cur]
            if top_errs:
                parts.append("top errors: " + ", ".join(f"{c}×{n}" for c, n in top_errs))
    except Exception as e:
        parts.append(f"action_log query failed: {e}")

    return "\n".join(parts)


@mcp.tool()
def tail_err_log(lines: int = 200) -> str:
    """Return the last N lines of taskbot.err.log. Capped at 1000 lines / 100KB."""
    return _tail_file(ERR_LOG, lines)


@mcp.tool()
def tail_out_log(lines: int = 100) -> str:
    """Return the last N lines of taskbot.out.log. Capped at 1000 lines / 100KB."""
    return _tail_file(OUT_LOG, lines)


@mcp.tool()
def recent_actions(limit: int = 20) -> str:
    """Last N rows from the action_log table — full detail per row.
    Includes timestamp, tool name, input summary, status, error category, result summary."""
    n = max(1, min(limit, MAX_DB_ROWS))
    with _connect_db() as conn:
        cur = conn.execute(
            "SELECT datetime(ts,'unixepoch','+8 hours') AS ts_local, tool_name, "
            "input_summary, status, error_category, result_summary "
            "FROM action_log ORDER BY id DESC LIMIT ?",
            (n,),
        )
        rows = cur.fetchall()
    return _format_rows(rows)


@mcp.tool()
def query_action_log(hours: int = 24, tool_name: str | None = None) -> str:
    """Aggregate action_log stats over a time window.
    Returns counts grouped by tool / status / error_category, ordered by frequency."""
    h = max(1, min(hours, 720))   # cap at 30 days
    sql = (
        "SELECT tool_name, status, error_category, COUNT(*) c "
        "FROM action_log WHERE ts > strftime('%s','now') - ? "
    )
    args: list = [h * 3600]
    if tool_name:
        sql += "AND tool_name = ? "
        args.append(tool_name)
    sql += "GROUP BY tool_name, status, error_category ORDER BY c DESC"
    with _connect_db() as conn:
        rows = conn.execute(sql, args).fetchall()
    return _format_rows(rows)


@mcp.tool()
def recent_conversation(limit: int = 10) -> str:
    """Last N messages from conversation_history (user + assistant alternating).
    Useful for understanding what the user just talked to the bot about."""
    n = max(1, min(limit, 100))
    with _connect_db() as conn:
        cur = conn.execute(
            "SELECT datetime(created_at,'unixepoch','+8 hours') AS ts_local, "
            "role, substr(content, 1, 500) AS content "
            "FROM conversation_history ORDER BY id DESC LIMIT ?",
            (n,),
        )
        rows = list(cur)
    if not rows:
        return "(no conversation history)"
    rows.reverse()  # oldest first
    out = []
    for r in rows:
        out.append(f"[{r['ts_local']}] {r['role']}: {r['content']}")
    return "\n".join(out)


@mcp.tool()
def recent_commits(hours: int = 24) -> str:
    """Recent git commits. `hours` defaults to 24. Caps at 30 days."""
    h = max(1, min(hours, 720))
    try:
        r = subprocess.run(
            ["git", "log", f"--since={h} hours ago", "--oneline", "-n", "50"],
            cwd=str(PROJECT_ROOT),
            capture_output=True, text=True, timeout=10,
        )
        return r.stdout.strip() or "(no commits in window)"
    except Exception as e:
        return f"git log failed: {e}"


@mcp.tool()
def db_query(sql: str) -> str:
    """Run a read-only SQL query against the bot's SQLite DB.
    SELECT statements only — anything else is rejected. Returns up to 500 rows."""
    if len(sql) > MAX_SQL_LENGTH:
        return f"SQL too long ({len(sql)} > {MAX_SQL_LENGTH})"
    stripped = sql.strip().rstrip(";").lstrip()
    # Reject anything that isn't a single SELECT or WITH-CTE-then-SELECT
    if not re.match(r"(?is)^(select|with)\b", stripped):
        return "Only SELECT / WITH queries are allowed."
    # Defence-in-depth: forbid dangerous keywords even inside what looks like a SELECT
    forbidden = re.search(
        r"(?is)\b(insert|update|delete|drop|alter|attach|detach|create|replace|pragma|vacuum)\b",
        stripped,
    )
    if forbidden:
        return f"Forbidden keyword: {forbidden.group(0).lower()}"
    with _connect_db() as conn:
        try:
            rows = conn.execute(stripped).fetchmany(MAX_DB_ROWS + 1)
        except sqlite3.Error as e:
            return f"sqlite error: {e}"
    return _format_rows(rows)


@mcp.tool()
def restart_bot() -> str:
    """Restart the taskbot via supervisorctl. Uses the existing NOPASSWD sudo
    rule. Returns supervisor's stdout/stderr."""
    try:
        r = subprocess.run(
            ["sudo", "-n", "/usr/bin/supervisorctl", "restart", "taskbot"],
            capture_output=True, text=True, timeout=20,
        )
        out = (r.stdout + r.stderr).strip()
        return out or "(no output)"
    except Exception as e:
        return f"restart failed: {e}"


# ── Auth middleware (bearer token) ─────────────────────────────────────────
#
# When transport=http, the server accepts requests from anywhere the tunnel
# is reachable. Without auth, anyone who learns the tunnel URL can hit
# /mcp endpoints and (e.g.) restart the bot. This middleware enforces a
# shared bearer token: requests without a matching Authorization header are
# rejected with 401 before they reach the MCP routes.
#
# The token is read from the MCP_AUTH_TOKEN env var. If unset, the server
# refuses to start in HTTP mode.

class TokenAuthMiddleware:
    """Accepts the auth token via either:
      • Authorization: Bearer <token>  header  (for curl / SDK testing)
      • Path prefix /<token>/...                 (for Claude Desktop's connector,
                                                  which only takes a URL)

    /health is always public (so we can curl-check from outside without leaking
    the token in shell history)."""

    def __init__(self, app, expected_token: str):
        self.app = app
        self.expected_token = expected_token

    async def _reject(self, send):
        await send({"type": "http.response.start", "status": 401,
                    "headers": [(b"content-type", b"application/json"),
                                (b"www-authenticate", b'Bearer realm="taskbot-mcp"')]})
        await send({"type": "http.response.body",
                    "body": b'{"error":"unauthorized"}'})

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        path = scope.get("path", "")

        # /health stays open — quick "is the tunnel up" check
        if path == "/health":
            await send({"type": "http.response.start", "status": 200,
                        "headers": [(b"content-type", b"text/plain")]})
            await send({"type": "http.response.body", "body": b"ok\n"})
            return

        # Path 1: Authorization: Bearer <token>
        headers = dict(scope.get("headers", []))
        auth = headers.get(b"authorization", b"").decode("latin-1", errors="replace")
        if secrets.compare_digest(auth, f"Bearer {self.expected_token}"):
            return await self.app(scope, receive, send)

        # Path 2: token as the first path segment, e.g. /<token>/mcp/...
        # Strip the token before forwarding so the inner MCP app sees /mcp/...
        parts = path.lstrip("/").split("/", 1)
        if parts and secrets.compare_digest(parts[0], self.expected_token):
            new_path = "/" + (parts[1] if len(parts) > 1 else "")
            new_scope = dict(scope)
            new_scope["path"] = new_path
            new_scope["raw_path"] = new_path.encode("utf-8")
            return await self.app(new_scope, receive, send)

        return await self._reject(send)


# ── Entrypoint ──────────────────────────────────────────────────────────────

def _run_http():
    """Run as an HTTP server (streamable-http transport) with bearer auth.
    Listens on 127.0.0.1:8765 by default — we expect Cloudflare Tunnel to
    front it, never expose this port directly to the internet."""
    import uvicorn

    token = os.environ.get("MCP_AUTH_TOKEN", "").strip()
    if not token:
        print("ERROR: MCP_AUTH_TOKEN must be set when running in HTTP mode.", file=sys.stderr)
        sys.exit(2)

    host = os.environ.get("MCP_HOST", "127.0.0.1")
    port = int(os.environ.get("MCP_PORT", "8765"))

    inner_app = mcp.streamable_http_app()
    app = TokenAuthMiddleware(inner_app, token)

    print(f"taskbot MCP listening on http://{host}:{port} (bearer auth required)", file=sys.stderr)
    uvicorn.run(app, host=host, port=port, log_level="warning")


if __name__ == "__main__":
    mode = os.environ.get("MCP_TRANSPORT", "stdio").lower()
    if mode == "http":
        _run_http()
    else:
        # Default: stdio transport (for SSH or local subprocess invocation)
        mcp.run()
