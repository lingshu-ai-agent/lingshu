#!/usr/bin/env python3
"""
Minimal MCP stdio server for demo-product (Story #025).

Implements the bare minimum of the Model Context Protocol over stdin/stdout:
- `initialize` → returns capabilities
- `initialized` notification → no response
- `tools/list` → returns 2 tools (echo, timestamp)
- `tools/call` → dispatches to the tool impl

Each message is a JSON-RPC 2.0 object on its own line. We use line-delimited
JSON framing because that's what StdioMcpServerConnection (dsh §6.5 (2.1))
expects.

No external dependencies — Python 3 stdlib only. Tests the McpTransport →
StdioMcpServerConnection → McpToolAdapter chain end-to-end without needing
npm/npx/python-virtualenv.

Run manually:
    python3 echo-stdio.py
"""
import datetime
import json
import sys


# ── Tool registry ──────────────────────────────────────────────────────────

TOOLS = [
    {
        "name": "echo",
        "description": "Echoes the input text back to the caller. Useful for verifying "
                       "the stdio MCP wire is healthy end-to-end.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to echo back"}
            },
            "required": ["text"]
        }
    },
    {
        "name": "timestamp",
        "description": "Returns the current server time as an ISO-8601 string. "
                       "Demonstrates a tool with no required input.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
]


def call_tool(name, arguments):
    """Dispatch a tools/call request to the named tool."""
    if name == "echo":
        text = arguments.get("text", "")
        return {"content": [{"type": "text", "text": "echo: " + text}]}
    if name == "timestamp":
        now = datetime.datetime.now(datetime.timezone.utc).isoformat()
        return {"content": [{"type": "text", "text": now}]}
    # MCP convention: surface unknown tools as an error result, not a protocol error.
    return {"content": [{"type": "text", "text": "unknown tool: " + name}],
            "isError": True}


# ── JSON-RPC dispatch loop ─────────────────────────────────────────────────

def send(obj):
    """Write one JSON-RPC message (followed by a newline) to stdout and flush."""
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def handle_request(req):
    """Handle one JSON-RPC request. Returns the response object, or None for notifications."""
    method = req.get("method")
    req_id = req.get("id")
    params = req.get("params") or {}

    if method == "initialize":
        # MCP handshake response — capabilities advert + serverInfo. The Java client
        # does not gate any feature on these fields, so we send a minimal but spec-valid
        # payload. See https://modelcontextprotocol.io/specification for full schema.
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "demo-product-echo", "version": "1.0.0"}
            }
        }

    if method == "notifications/initialized":
        # Notification, not a request — no response. StdioMcpServerConnection sends
        # this after a successful initialize to acknowledge readiness.
        return None

    if method == "tools/list":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {"tools": TOOLS}
        }

    if method == "tools/call":
        name = params.get("name", "")
        arguments = params.get("arguments") or {}
        result = call_tool(name, arguments)
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": result
        }

    if method == "ping":
        # Heartbeat probe — StdioMcpServerConnection issues this on the heartbeat
        # schedule. Empty result object is the conventional reply.
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}

    # Unknown method: return a JSON-RPC error, not a crash.
    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": -32601, "message": "method not found: " + str(method)}
    }


def main():
    """Read line-delimited JSON requests from stdin, write responses to stdout."""
    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError as e:
            # Per JSON-RPC 2.0, malformed input is a parse error. We don't have
            # an id, so we can't reply — just log to stderr.
            sys.stderr.write("parse error: " + str(e) + "\n")
            continue
        response = handle_request(req)
        if response is not None:
            send(response)


if __name__ == "__main__":
    main()