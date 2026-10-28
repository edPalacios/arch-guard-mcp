#!/usr/bin/env python3
"""Call one tool on the arch-guard MCP server over stdio and print its result.

Usage:
    scripts/mcp_call.py <tool> '<json arguments>' [--jar build/libs/arch-guard-mcp-all.jar]
    scripts/mcp_call.py --list

Prints the tool result as JSON (structuredContent when present, else the text content)
and exits 1 when the result has isError = true, 2 on a protocol problem.
Also fails if the server writes anything to stdout that is not a JSON-RPC message.
"""
import json
import subprocess
import sys

DEFAULT_JAR = "build/libs/arch-guard-mcp-all.jar"


def main() -> int:
    args = sys.argv[1:]
    jar = DEFAULT_JAR
    if "--jar" in args:
        i = args.index("--jar")
        jar = args[i + 1]
        del args[i:i + 2]

    if args == ["--list"]:
        request = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
    elif len(args) == 2:
        request = {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                   "params": {"name": args[0], "arguments": json.loads(args[1])}}
    else:
        print(__doc__, file=sys.stderr)
        return 2

    messages = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize",
         "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                    "clientInfo": {"name": "mcp_call", "version": "1"}}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        request,
    ]
    proc = subprocess.Popen(["java", "-jar", jar], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    for message in messages:
        proc.stdin.write(json.dumps(message) + "\n")
    proc.stdin.flush()

    try:
        for line in proc.stdout:
            try:
                response = json.loads(line)
            except json.JSONDecodeError:
                print(f"stdout is polluted with non JSON-RPC output: {line!r}", file=sys.stderr)
                return 2
            if response.get("id") != 2:
                continue
            if "error" in response:
                print(json.dumps(response["error"], indent=2))
                return 2
            result = response["result"]
            if args == ["--list"]:
                print(json.dumps(result, indent=2))
                return 0
            body = result.get("structuredContent")
            if body is None:
                body = [c.get("text") for c in result.get("content", [])]
            print(json.dumps(body, indent=2))
            return 1 if result.get("isError") else 0
        print("server closed stdout without answering", file=sys.stderr)
        return 2
    finally:
        proc.stdin.close()
        proc.kill()


if __name__ == "__main__":
    sys.exit(main())
