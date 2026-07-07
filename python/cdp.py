"""Chrome DevTools Protocol client — navigate and evaluate JS in Android Chrome via ADB forward."""
import asyncio
import json
import logging
from typing import Any, Optional

import aiohttp
import websockets

log = logging.getLogger(__name__)


class CDPClient:
    def __init__(self, port: int = 9222):
        self._port = port
        self._ws: Optional[websockets.WebSocketClientProtocol] = None
        self._msg_id = 0
        self._pending: dict[int, asyncio.Future] = {}
        self._recv_task: Optional[asyncio.Task] = None

    async def connect(self, retries: int = 15, retry_delay: float = 1.0) -> None:
        ws_url = await self._get_tab_ws_url(retries, retry_delay)
        self._ws = await websockets.connect(ws_url, max_size=None, open_timeout=10)
        self._recv_task = asyncio.get_event_loop().create_task(self._recv_loop())
        log.info("CDP connected: %s", ws_url)

    async def close(self) -> None:
        for fut in self._pending.values():
            if not fut.done():
                fut.cancel()
        self._pending.clear()

        if self._recv_task:
            self._recv_task.cancel()
            try:
                await self._recv_task
            except (asyncio.CancelledError, Exception):
                pass
            self._recv_task = None

        if self._ws:
            try:
                await self._ws.close()
            except Exception:
                pass
            self._ws = None

    async def navigate(self, url: str) -> None:
        await self._send("Page.navigate", {"url": url})
        log.info("CDP navigate: %s", url)

    async def evaluate(self, expression: str, timeout: float = 30.0) -> Any:
        result = await asyncio.wait_for(
            self._send("Runtime.evaluate", {
                "expression": expression,
                "returnByValue": True,
                "awaitPromise": False,
            }),
            timeout=timeout,
        )
        val = result.get("result", {})
        exc = result.get("exceptionDetails")
        if exc:
            raise RuntimeError(f"JS exception: {exc.get('exception', {}).get('description', exc)}")
        if val.get("subtype") == "error":
            raise RuntimeError(f"JS error: {val.get('description', '')}")
        return val.get("value")

    # ── Internals ─────────────────────────────────────────────────────────────

    async def _get_tab_ws_url(self, retries: int, delay: float) -> str:
        last_err: Exception | None = None
        async with aiohttp.ClientSession() as session:
            for attempt in range(retries):
                try:
                    async with session.get(
                        f"http://localhost:{self._port}/json",
                        timeout=aiohttp.ClientTimeout(total=5),
                    ) as resp:
                        tabs = await resp.json(content_type=None)
                        for tab in tabs:
                            if tab.get("type") == "page":
                                url = tab.get("webSocketDebuggerUrl")
                                if url:
                                    return url
                        raise RuntimeError("No page tab found in CDP /json")
                except Exception as e:
                    last_err = e
                    log.debug("CDP not ready yet (attempt %d/%d): %s", attempt + 1, retries, e)
                    if attempt < retries - 1:
                        await asyncio.sleep(delay)
        raise RuntimeError(f"CDP connect failed after {retries} attempts: {last_err}")

    async def _recv_loop(self) -> None:
        try:
            async for raw in self._ws:
                data = json.loads(raw)
                msg_id = data.get("id")
                fut = self._pending.pop(msg_id, None)
                if fut and not fut.done():
                    fut.set_result(data)
        except asyncio.CancelledError:
            raise
        except Exception as e:
            log.debug("CDP recv loop ended: %s", e)
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(e)

    async def _send(self, method: str, params: dict | None = None) -> dict:
        if not self._ws:
            raise RuntimeError("CDP not connected")
        self._msg_id += 1
        msg_id = self._msg_id
        fut: asyncio.Future = asyncio.get_event_loop().create_future()
        self._pending[msg_id] = fut
        await self._ws.send(json.dumps({"id": msg_id, "method": method, "params": params or {}}))
        result = await asyncio.wait_for(fut, timeout=30)
        if "error" in result:
            raise RuntimeError(f"CDP {method} error: {result['error']}")
        return result.get("result", {})
