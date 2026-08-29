# /// script
# requires-python = ">=3.9"
# dependencies = []
# ///
"""Push a stream URL to the kiosk player's Setup > Receive panel.

Usage:  uv run tools/pushstream.py --host 192.168.1.42

The tablet shows its address when you press Receive. Everything except the
host is prompted, so a credentialed URL never lands in shell history.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

PORT = 8081
TIMEOUT_S = 10


def prompt(label):
    try:
        return input(label).strip()
    except (EOFError, KeyboardInterrupt):
        sys.exit("\naborted")


def post(host, payload):
    """Returns the decoded response body. Raises on transport or HTTP failure."""
    request = urllib.request.Request(
        f"http://{host}:{PORT}/add",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
        return json.loads(response.read().decode())


def describe(error):
    """The tablet's rejection text, or a fallback if the body is not ours."""
    try:
        return json.loads(error.read().decode()).get("error", "")
    except (ValueError, OSError):
        return f"HTTP {error.code}"


def main():
    parser = argparse.ArgumentParser(description="Push a stream URL to the kiosk player.")
    parser.add_argument("--host", help="the tablet's IP, shown on its Receive panel")
    args = parser.parse_args()

    host = args.host or prompt("Tablet IP: ")
    if not host:
        sys.exit("no host given")

    while True:
        url = prompt("Stream URL (blank to finish): ")
        if not url:
            return 0
        name = prompt("Name (blank for none): ")

        payload = {"url": url}
        if name:
            payload["name"] = name

        try:
            body = post(host, payload)
        except urllib.error.HTTPError as error:
            # The tablet is the only authority on what a valid URL is, so its
            # reason is printed verbatim and the loop re-prompts.
            print(f"rejected: {describe(error)}", file=sys.stderr)
            continue
        except urllib.error.URLError as error:
            sys.exit(f"nothing on {host}:{PORT} — is the Receive panel open? ({error.reason})")

        print(f'added "{body.get("name") or "<no name>"}"')


if __name__ == "__main__":
    sys.exit(main())
