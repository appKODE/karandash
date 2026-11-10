#!/usr/bin/env python3
"""TLS front for the mock server: `tls_proxy.py <tls-port> <mock-port> <cert.pem> <key.pem>`.

Features that insist on HTTPS (authenticated `extends:`, the plugin registry and its downloads) reach the plain-HTTP
mock through https://localhost:<tls-port>. Requests are forwarded verbatim, Host header included, so the mock's
request log and its mock_*.py modules see them as if they had arrived directly; nothing is followed or rewritten.
"""

import http.client
import os
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TLS_PORT, MOCK_PORT, CERT, KEY = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4]
HOP_BY_HOP = {"connection", "keep-alive", "transfer-encoding", "content-length"}


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _forward(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
        upstream = http.client.HTTPConnection("127.0.0.1", MOCK_PORT, timeout=30)
        upstream.request(self.command, self.path, body or None, dict(self.headers.items()))
        answer = upstream.getresponse()
        data = answer.read()
        self.send_response(answer.status)
        for name, value in answer.getheaders():
            if name.lower() not in HOP_BY_HOP:
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        upstream.close()

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _forward

    def log_message(self, format, *args):
        pass


def exit_with_parent():
    parent = os.getppid()
    while os.getppid() == parent:
        time.sleep(1)
    os._exit(0)


threading.Thread(target=exit_with_parent, daemon=True).start()
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(CERT, KEY)
server = ThreadingHTTPServer(("127.0.0.1", TLS_PORT), Proxy)
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
