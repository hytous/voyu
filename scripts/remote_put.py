import argparse
import os
import posixpath
import sys
import time



import paramiko


def connect():
    host = os.environ["VOYU_REMOTE_HOST"]
    user = os.environ["VOYU_REMOTE_USER"]
    password = os.environ["VOYU_REMOTE_PASSWORD"]
    port = int(os.environ.get("VOYU_REMOTE_PORT", "22"))
    attempts = int(os.environ.get("VOYU_REMOTE_CONNECT_RETRIES", "5"))
    delay_seconds = float(os.environ.get("VOYU_REMOTE_CONNECT_DELAY", "2"))

    last_error = None
    for attempt in range(1, attempts + 1):
        transport = paramiko.Transport((host, port))
        try:
            transport.banner_timeout = 30
            transport.connect(username=user, password=password)
            return transport
        except (paramiko.SSHException, EOFError, TimeoutError, OSError) as exc:
            transport.close()
            last_error = exc
            if attempt == attempts:
                raise
            time.sleep(delay_seconds)

    raise last_error


def ensure_dirs(sftp, remote_path):
    parts = remote_path.strip("/").split("/")
    current = ""
    for part in parts[:-1]:
        current = current + "/" + part
        try:
            sftp.stat(current)
        except FileNotFoundError:
            sftp.mkdir(current)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--local", required=True)
    parser.add_argument("--remote", required=True)
    args = parser.parse_args()

    transport = connect()
    try:
        sftp = paramiko.SFTPClient.from_transport(transport)
        remote_path = posixpath.normpath(args.remote)
        ensure_dirs(sftp, remote_path)
        sftp.put(args.local, remote_path)
    finally:
        transport.close()


if __name__ == "__main__":
    main()
