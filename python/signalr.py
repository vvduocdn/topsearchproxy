"""Async SignalR JSON protocol client — mirrors SignalRClient.kt behaviour."""
import asyncio
import json
import logging
from dataclasses import dataclass
from typing import Awaitable, Callable, List, Optional

import websockets

log = logging.getLogger(__name__)

RS = ""          # SignalR JSON protocol message delimiter (ASCII 30)
PING_INTERVAL = 15     # seconds between client-initiated type=6 pings


@dataclass
class SocketRequest:
    request_id: str
    keyword:    str
    proxy:      str
    country:    int


class SignalRClient:
    def __init__(
        self,
        url:      str,
        on_batch: Callable[[List[SocketRequest]], Awaitable[None]],
    ):
        self._url      = url
        self._on_batch = on_batch
        self._ws: Optional[websockets.WebSocketClientProtocol] = None
        self._connected = False

    @property
    def is_connected(self) -> bool:
        return self._connected

    async def run_forever(self) -> None:
        retry_delay = 1.0
        while True:
            try:
                log.info("Connecting to SignalR…")
                async with websockets.connect(self._url, ping_interval=None) as ws:
                    self._ws = ws
                    await ws.send(f'{{"protocol":"json","version":1}}{RS}')

                    connected = False
                    ping_task: Optional[asyncio.Task] = None

                    async for message in ws:
                        for frame in message.split(RS):
                            frame = frame.strip()
                            if not frame:
                                continue
                            try:
                                data = json.loads(frame)
                            except json.JSONDecodeError:
                                continue

                            if not connected:
                                err = data.get("error", "")
                                if err:
                                    log.error("Handshake rejected: %s", err)
                                    break
                                connected = True
                                self._connected = True
                                retry_delay = 1.0
                                log.info("SignalR connected")
                                ping_task = asyncio.get_event_loop().create_task(
                                    self._ping_loop(ws)
                                )
                                continue

                            msg_type = data.get("type")
                            if msg_type == 1:
                                await self._handle_invocation(data)
                            elif msg_type == 6:
                                log.debug("Server ping received")

                    if ping_task:
                        ping_task.cancel()
                    self._connected = False
                    self._ws = None
                    log.warning("SignalR disconnected")

            except Exception as e:
                log.error("SignalR error: %s", e)
                self._connected = False
                self._ws = None

            log.info("Reconnecting in %.0fs…", retry_delay)
            await asyncio.sleep(retry_delay)
            retry_delay = min(retry_delay * 2, 60.0)

    async def submit(
        self,
        request_id:  str,
        items:       list,
        image_url:   str,
        public_ip:   str,
        source_name: str,
        checked_at:  int,
    ) -> bool:
        if not self._ws or not self._connected:
            log.error("Cannot submit: not connected (reqId=%s)", request_id)
            return False

        top10 = items[:10]
        payload = {
            "requestId":      request_id,
            "items":          [{"top": r["rank"], "url": r["url"], "domain": r["domain"]} for r in top10],
            "mobileImageUrl": image_url  or None,
            "publicIp":       public_ip  or None,
            "sourceName":     source_name or None,
            "checkedAt":      checked_at if checked_at > 0 else None,
        }
        msg = json.dumps({"type": 1, "target": "SubmitMobileResult", "arguments": [payload]}) + RS

        try:
            await self._ws.send(msg)
            log.info("Submitted reqId=%s items=%d", request_id, len(top10))
            return True
        except Exception as e:
            log.error("Submit failed reqId=%s: %s", request_id, e)
            return False

    async def _ping_loop(self, ws) -> None:
        try:
            while True:
                await asyncio.sleep(PING_INTERVAL)
                if self._connected:
                    await ws.send(f'{{"type":6}}{RS}')
                    log.debug("Ping sent")
        except Exception:
            pass

    async def _handle_invocation(self, data: dict) -> None:
        target = data.get("target", "")
        args   = data.get("arguments", [])

        if target in ("CheckKeywords", "CheckTestKeywords"):
            if not args or not isinstance(args[0], list):
                return
            batch = [
                SocketRequest(
                    request_id=item.get("requestId", ""),
                    keyword=item.get("keyword", ""),
                    proxy=item.get("proxy", ""),
                    country=item.get("country", 1),
                )
                for item in args[0]
                if item.get("requestId") and item.get("keyword")
            ]
            if batch:
                log.info("%s: %d keyword(s)", target, len(batch))
                await self._on_batch(batch)

        elif target == "CheckKeyword":
            if not args or not isinstance(args[0], dict):
                return
            item = args[0]
            req_id  = item.get("requestId", "")
            keyword = item.get("keyword", "")
            if req_id and keyword:
                await self._on_batch([SocketRequest(
                    request_id=req_id,
                    keyword=keyword,
                    proxy=item.get("proxy", ""),
                    country=item.get("country", 1),
                )])
        else:
            log.debug("Unknown invocation target: %s", target)
