import importlib.util
import asyncio
from pathlib import Path
import socket
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("browser_session", Path(__file__).with_name("session.py"))
session = importlib.util.module_from_spec(spec)
spec.loader.exec_module(session)


class UrlPolicyTest(unittest.TestCase):
    def test_rejects_private_addresses_credentials_and_non_web_schemes(self):
        for url in ("file:///etc/passwd", "https://owner:secret@example.com"):
            with self.assertRaises(ValueError):
                session.public_url(url)
        with patch.object(socket, "getaddrinfo", return_value=[(2, 1, 6, "", ("127.0.0.1", 80))]):
            with self.assertRaises(ValueError):
                session.public_url("http://example.com")
        with patch.object(socket, "getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]):
            self.assertEqual("example.com", session.public_url("https://example.com/article"))
        with patch.object(socket, "getaddrinfo", return_value=[
                (2, 1, 6, "", ("93.184.216.34", 443)), (2, 1, 6, "", ("192.168.1.1", 443))]):
            with self.assertRaises(ValueError):
                session.public_url("https://example.com")


class ProxyTest(unittest.IsolatedAsyncioTestCase):
    async def test_private_connect_is_rejected_before_upstream_socket(self):
        server = await asyncio.start_server(session.proxy_connection, "127.0.0.1", 0)
        async with server:
            reader, writer = await asyncio.open_connection("127.0.0.1", server.sockets[0].getsockname()[1])
            writer.write(b"CONNECT 127.0.0.1:8080 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            await writer.drain()
            self.assertTrue((await asyncio.wait_for(reader.read(), 3)).startswith(b"HTTP/1.1 403"))
            writer.close()
            await writer.wait_closed()


if __name__ == "__main__":
    unittest.main()
