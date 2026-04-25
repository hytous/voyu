import argparse
import os
import sys
import time



import paramiko

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def connect():
    host = os.environ["VOYU_REMOTE_HOST"]
    user = os.environ["VOYU_REMOTE_USER"]
    password = os.environ["VOYU_REMOTE_PASSWORD"]
    port = int(os.environ.get("VOYU_REMOTE_PORT", "22"))
    attempts = int(os.environ.get("VOYU_REMOTE_CONNECT_RETRIES", "5"))
    delay_seconds = float(os.environ.get("VOYU_REMOTE_CONNECT_DELAY", "2"))

    last_error = None
    for attempt in range(1, attempts + 1):
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            client.connect(
                hostname=host,
                port=port,
                username=user,
                password=password,
                timeout=20,
                banner_timeout=30,
                auth_timeout=20,
            )
            return client
        except (paramiko.SSHException, EOFError, TimeoutError, OSError) as exc:
            client.close()
            last_error = exc
            if attempt == attempts:
                raise
            time.sleep(delay_seconds)

    raise last_error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--command")
    parser.add_argument("--workdir")
    args = parser.parse_args()

    command = args.command or os.environ.get("VOYU_REMOTE_COMMAND")
    if not command:
        raise SystemExit("missing command: use --command or VOYU_REMOTE_COMMAND")

    client = connect()
    try:
        if args.workdir:
            command = f"cd {shell_quote(args.workdir)} && {command}"
        stdin, stdout, stderr = client.exec_command(command, timeout=600)
        exit_status = stdout.channel.recv_exit_status()
        sys.stdout.write(stdout.read().decode("utf-8", errors="replace"))
        sys.stderr.write(stderr.read().decode("utf-8", errors="replace"))
        raise SystemExit(exit_status)
    finally:
        client.close()


def shell_quote(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


if __name__ == "__main__":
    main()
