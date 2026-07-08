"""Chrome DevTools Protocol client — navigate and evaluate JS in Android Chrome via ADB forward."""
import asyncio
import base64
import json
import logging
import time
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
        self._target_id: Optional[str] = None

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

    async def scroll_to_bottom(self, step: int = 400, delay: float = 0.45, max_height: int = 12000) -> None:
        """Scroll to bottom via window.scrollBy — works on all viewport sizes.
        Avoids Input.synthesizeScrollGesture which fails with 'Position out of bounds'
        on some viewport/DPR combinations (e.g. 360x700 at DPR 3.0)."""
        info_raw = await self.evaluate(
            "(function(){return JSON.stringify({"
            "h:Math.max(document.body.scrollHeight||0,document.documentElement.scrollHeight||0),"
            "vh:window.innerHeight||700,vw:window.innerWidth||360})})()"
        )
        try:
            info  = json.loads(info_raw) if info_raw else {}
            total = min(int(info.get("h", 5000)), max_height)
            vh    = int(info.get("vh", 700))
            vw    = int(info.get("vw", 360))
        except Exception:
            total, vh, vw = 5000, 700, 360

        log.info("Scroll: page=%dpx viewport=%dx%d", total, vw, vh)

        await self.evaluate("window.scrollTo(0,0)")
        await asyncio.sleep(0.4)

        scrolled = 0
        while scrolled < total:
            chunk = min(step, total - scrolled)
            await self.evaluate(f"window.scrollBy(0,{chunk})")
            scrolled += chunk
            try:
                h_raw = await self.evaluate(
                    "Math.max(document.body.scrollHeight||0,document.documentElement.scrollHeight||0)"
                )
                new_h = min(int(h_raw or total), max_height)
                if new_h > total:
                    total = new_h
            except Exception:
                pass
            await asyncio.sleep(delay)

        log.info("Scroll done (scrolled=%dpx)", scrolled)

    async def inject_overlay(self, ip: str, keyword: str) -> None:
        """Inject a compact side overlay showing IP, keyword, and current time."""
        now = time.strftime("%d/%m/%Y")
        safe_ip      = ip.replace("'", "").replace("<", "").replace(">", "")
        safe_keyword = keyword.replace("'", "").replace("\\", "").replace("<", "").replace(">", "")
        safe_now     = now.replace("'", "")
        js = (
            "(function(){"
            "var o=document.getElementById('__ov');"
            "if(o)o.remove();"
            "var d=document.createElement('div');"
            "d.id='__ov';"
            "d.style.cssText='"
            "position:fixed;bottom:24px;right:8px;"
            "background:rgba(0,0,0,0.78);color:#fff;"
            "font-size:11px;padding:6px 8px;z-index:2147483647;"
            "font-family:monospace;border-radius:6px;"
            "pointer-events:none;max-width:150px;"
            "word-break:break-all;line-height:1.5;"
            "';"
            f"d.innerHTML='<div>IP: {safe_ip}</div>"
            f"<div>{safe_keyword}</div>"
            f"<div>{safe_now}</div>';"
            "document.body.appendChild(d);"
            "})()"
        )
        await self.evaluate(js)
        log.info("Overlay injected: ip=%s keyword=%r time=%s", ip, keyword, now)

    async def screenshot_full_page(self, path: str, quality: int = 75, max_height: int = 8000) -> None:
        """Scroll to the bottom in DOM coordinates then capture one viewport screenshot."""
        await self.evaluate(
            "window.scrollTo(0,Math.max(document.body.scrollHeight||0,document.documentElement.scrollHeight||0))"
        )
        await asyncio.sleep(0.4)
        result = await self._send("Page.captureScreenshot", {"format": "jpeg", "quality": quality})
        img_data = result.get("data", "")
        if not img_data:
            raise RuntimeError("Page.captureScreenshot returned no data")
        with open(path, "wb") as f:
            f.write(base64.b64decode(img_data))
        log.info("Screenshot saved: %s", path)

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
                        # Prefer real web tabs over chrome-native:// (newtab, etc.)
                        preferred: tuple | None = None
                        fallback: tuple | None = None
                        for tab in tabs:
                            if tab.get("type") != "page":
                                continue
                            ws = tab.get("webSocketDebuggerUrl")
                            if not ws:
                                continue
                            if tab.get("url", "").startswith("chrome-native://"):
                                if fallback is None:
                                    fallback = (ws, tab.get("id"))
                            else:
                                if preferred is None:
                                    preferred = (ws, tab.get("id"))
                        chosen = preferred or fallback
                        if chosen:
                            ws_url, target_id = chosen
                            self._target_id = target_id
                            if preferred and fallback:
                                log.info("Skipped chrome-native:// tab, connected to real page tab")
                            return ws_url
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
