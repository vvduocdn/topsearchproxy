"""Local HTTP proxy with auth forwarding — Python port of LocalProxyServer.kt.

Android's system proxy setting only supports unauthenticated proxies.
For authenticated proxies: Android points to this local server (no auth),
which adds Proxy-Authorization before forwarding to the real proxy.
"""
import asyncio
import base64
import logging

log = logging.getLogger(__name__)
_BUF = 8192


class LocalProxyServer:
    def __init__(self, remote_host: str, remote_port: int, user: str, password: str):
        self._remote_host = remote_host
        self._remote_port = remote_port
        self._auth_header = self._make_auth_header(user, password)
        self._server: asyncio.Server | None = None
        self.local_port = 0

    @staticmethod
    def _make_auth_header(user: str, password: str) -> bytes:
        b64 = base64.b64encode(f"{user}:{password}".encode()).decode()
        return f"Proxy-Authorization: Basic {b64}\r\n".encode()

    async def start(self) -> None:
        self._server = await asyncio.start_server(self._handle, "0.0.0.0", 0)
        self.local_port = self._server.sockets[0].getsockname()[1]
        log.info("LocalProxy started :%d -> %s:%d", self.local_port, self._remote_host, self._remote_port)

    async def stop(self) -> None:
        if self._server:
            self._server.close()
            try:
                await asyncio.wait_for(self._server.wait_closed(), timeout=5.0)
            except asyncio.TimeoutError:
                pass
            self._server = None
            log.info("LocalProxy stopped")

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            headers = await _read_headers(reader)
            if not headers:
                log.debug("LocalProxy: empty request")
                return
            req = headers[0]
            log.debug("LocalProxy: %s", req[:100])
            if req.startswith("CONNECT "):
                await self._handle_connect(req, reader, writer, headers)
            else:
                await self._handle_http(req, headers, reader, writer)
        except Exception as e:
            log.debug("LocalProxy handle error: %s", e)
        finally:
            _close(writer)

    async def _handle_connect(self, req: str, cr: asyncio.StreamReader, cw: asyncio.StreamWriter, _hdrs) -> None:
        parts = req.split(" ")
        if len(parts) < 2:
            return
        target = parts[1]
        try:
            rr, rw = await asyncio.wait_for(
                asyncio.open_connection(self._remote_host, self._remote_port),
                timeout=15,
            )
            try:
                rw.write(f"{req}\r\n".encode() + self._auth_header + f"Host: {target}\r\n\r\n".encode())
                await rw.drain()

                resp = await _read_headers(rr)
                status = resp[0] if resp else ""
                log.info("CONNECT %s -> %s", target, status)

                if "200" not in status:
                    log.warning("CONNECT %s: proxy rejected (%s)", target, status)
                    cw.write(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
                    await cw.drain()
                    return

                cw.write(b"HTTP/1.1 200 Connection established\r\n\r\n")
                await cw.drain()
                await _relay(cr, cw, rr, rw)
            finally:
                _close(rw)
        except asyncio.TimeoutError:
            log.warning("CONNECT %s: timeout connecting to %s:%d", target, self._remote_host, self._remote_port)
        except Exception as e:
            log.warning("CONNECT %s: %s", target, e)

    async def _handle_http(self, req: str, headers: list[str], cr: asyncio.StreamReader, cw: asyncio.StreamWriter) -> None:
        try:
            rr, rw = await asyncio.wait_for(
                asyncio.open_connection(self._remote_host, self._remote_port),
                timeout=15,
            )
            try:
                rw.write(f"{req}\r\n".encode())
                rw.write(self._auth_header)
                for h in headers[1:]:
                    if not h.lower().startswith("proxy-authorization:"):
                        rw.write(f"{h}\r\n".encode())
                rw.write(b"\r\n")
                await rw.drain()
                await _relay(cr, cw, rr, rw)
            finally:
                _close(rw)
        except asyncio.TimeoutError:
            log.warning("HTTP proxy timeout connecting to %s:%d", self._remote_host, self._remote_port)
        except Exception as e:
            log.debug("HTTP proxy error: %s", e)


async def _read_headers(reader: asyncio.StreamReader) -> list[str]:
    lines: list[str] = []
    while True:
        try:
            raw = await asyncio.wait_for(reader.readline(), timeout=10)
        except (asyncio.TimeoutError, Exception):
            break
        if not raw:
            break
        line = raw.decode("latin-1").rstrip("\r\n")
        if not line:
            break
        lines.append(line)
    return lines


async def _relay(cr: asyncio.StreamReader, cw: asyncio.StreamWriter,
                 rr: asyncio.StreamReader, rw: asyncio.StreamWriter) -> None:
    async def pipe(src: asyncio.StreamReader, dst: asyncio.StreamWriter) -> None:
        try:
            while True:
                chunk = await src.read(_BUF)
                if not chunk:
                    break
                dst.write(chunk)
                await dst.drain()
        except Exception:
            pass
        finally:
            _close(dst)

    await asyncio.gather(pipe(cr, rw), pipe(rr, cw), return_exceptions=True)


def _close(writer: asyncio.StreamWriter) -> None:
    try:
        writer.close()
    except Exception:
        pass
