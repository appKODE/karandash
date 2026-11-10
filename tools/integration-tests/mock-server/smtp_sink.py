"""Minimal plaintext SMTP relay for the e2e: smtp_sink.py <port> <log-file>.

Plays a local relay (postfix on loopback): no STARTTLS, no AUTH, accepts every message. Every line the client
sends, including the DATA payload (headers and body), is appended to <log-file>, so a test can assert the envelope
(MAIL FROM / RCPT TO) and the delivered subject and body. After each message the decoded text parts follow, so
a body sent base64 (the native client) is as searchable as a plain one. A client that insists on STARTTLS gives up after EHLO.
Exits by itself after 5 minutes if nobody stops it.
"""

import email
import signal
import socketserver
import sys

LOG = sys.argv[2]


def write_decoded(raw):
    parsed = email.message_from_string(raw)
    with open(LOG, "a", encoding="utf-8") as f:
        for part in parsed.walk():
            if part.get_content_maintype() == "text":
                f.write(part.get_payload(decode=True).decode("utf-8", "replace") + "\n")


class Handler(socketserver.StreamRequestHandler):
    def reply(self, line):
        self.wfile.write(line.encode() + b"\r\n")
        self.wfile.flush()

    def handle(self):
        self.reply("220 smtp-sink ready")
        in_data = False
        message = []
        for raw in self.rfile:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            with open(LOG, "a", encoding="utf-8") as f:
                f.write(line + "\n")
            if in_data:
                if line == ".":
                    in_data = False
                    write_decoded("\n".join(message))
                    message = []
                    self.reply("250 queued")
                else:
                    message.append(line)
                continue
            verb = line.split(" ")[0].upper()
            if verb in ("EHLO", "HELO"):
                self.reply("250 smtp-sink")
            elif verb == "DATA":
                in_data = True
                self.reply("354 end with <CRLF>.<CRLF>")
            elif verb == "QUIT":
                self.reply("221 bye")
                return
            elif verb in ("MAIL", "RCPT", "RSET", "NOOP"):
                self.reply("250 ok")
            else:
                self.reply("502 command not implemented")


if __name__ == "__main__":
    signal.alarm(300)
    with socketserver.ThreadingTCPServer(("127.0.0.1", int(sys.argv[1])), Handler) as server:
        server.daemon_threads = True
        server.serve_forever()
