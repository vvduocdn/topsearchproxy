"""Keyword processing: ADB proxy setup → Chrome via CDP → extract → Telegram → submit.

One keyword at a time (semaphore) since there is one Android device / one Chrome session.
"""
import asyncio
import json
import logging
import os
import tempfile
import time
from typing import Optional

import aiohttp

from adb import ADB
from cdp import CDPClient
from config import ADB_SERIAL, CDP_LOCAL_PORT, DOM_POLL_INTERVAL, GOOGLE_URL, SEARCH_TIMEOUT
from extract_js import (
    ACCEPT_CONSENT_JS,
    EXTRACT_HEADINGS_IN_IMAGE_ORDER_JS,
    WAIT_READY_JS,
    build_search_js,
)
from local_proxy import LocalProxyServer
from signalr import SignalRClient, SocketRequest
import telegram

log = logging.getLogger(__name__)

_device_lock = asyncio.Semaphore(1)


async def process(req: SocketRequest, source_name: str, client: SignalRClient) -> None:
    async with _device_lock:
        await _do_process(req, source_name, client)


# ── Main flow ─────────────────────────────────────────────────────────────────

async def _do_process(req: SocketRequest, source_name: str, client: SignalRClient) -> None:
    adb         = ADB(serial=ADB_SERIAL, cdp_port=CDP_LOCAL_PORT)
    local_proxy: Optional[LocalProxyServer] = None
    cdp:         Optional[CDPClient]        = None
    screenshot:  Optional[str]              = None

    try:
        proxy_info = _parse_proxy(req.proxy)
        log.info("START reqId=%s keyword='%s' proxy=%s", req.request_id, req.keyword, req.proxy)

        # ── 1. Stop Chrome → set proxy → restart Chrome ────────────────────
        await adb.force_stop_chrome()

        if proxy_info:
            if proxy_info["user"]:
                local_proxy = LocalProxyServer(
                    remote_host=proxy_info["host"],
                    remote_port=proxy_info["port"],
                    user=proxy_info["user"],
                    password=proxy_info["pass"],
                )
                await local_proxy.start()
                pc_ip = adb.get_local_ip()
                await adb.set_proxy(pc_ip, local_proxy.local_port)
            else:
                await adb.set_proxy(proxy_info["host"], proxy_info["port"])
        else:
            await adb.clear_proxy()

        # ── 2. Forward CDP + launch Chrome ────────────────────────────────
        await adb.forward_cdp()
        await adb.launch_chrome(GOOGLE_URL)
        await asyncio.sleep(2.5)

        # ── 3. Connect CDP ─────────────────────────────────────────────────
        cdp = CDPClient(port=CDP_LOCAL_PORT)
        await cdp.connect(retries=15, retry_delay=1.0)

        # Navigate to Google (handles cases where Chrome opened elsewhere)
        await cdp.navigate(GOOGLE_URL)
        await _wait_dom(cdp, min_count=1, timeout=15)

        # ── 4. Accept Google consent if shown ─────────────────────────────
        consent_raw = await _safe_eval(cdp, ACCEPT_CONSENT_JS)
        if consent_raw:
            try:
                cr = json.loads(consent_raw)
                if cr.get("clicked"):
                    log.info("Consent clicked: %s", cr.get("reason"))
                    await asyncio.sleep(2)
                    await _wait_dom(cdp, min_count=1, timeout=10)
            except Exception:
                pass

        # ── 5. Submit search ───────────────────────────────────────────────
        search_ok = await _safe_eval(cdp, build_search_js(req.keyword))
        log.info("Search submit result: %s", search_ok)
        await asyncio.sleep(2)

        # ── 6. Wait for results ────────────────────────────────────────────
        await _wait_dom(cdp, min_count=10, timeout=SEARCH_TIMEOUT)
        checked_at = int(time.time() * 1000)

        # ── 7. Extract ────────────────────────────────────────────────────
        raw = await _safe_eval(cdp, EXTRACT_HEADINGS_IN_IMAGE_ORDER_JS, timeout=30)
        if not raw:
            raise RuntimeError("Extraction returned empty result")

        items_raw = json.loads(raw)
        items = _build_results(items_raw)

        if not items:
            raise RuntimeError("No valid results after filtering")

        dup_reason = _check_duplicate_domain(items)
        if dup_reason:
            raise RuntimeError(dup_reason)

        # ── 8. Screenshot ─────────────────────────────────────────────────
        _fd, screenshot = tempfile.mkstemp(suffix=".jpg")
        os.close(_fd)
        await adb.take_screenshot(screenshot)

        # ── 9. Public IP ──────────────────────────────────────────────────
        public_ip = await _get_public_ip(proxy_info, local_proxy)

        # ── 10. Telegram ──────────────────────────────────────────────────
        image_url = await telegram.upload(screenshot)
        if not image_url:
            raise RuntimeError("Telegram upload failed")

        result_msg = telegram.build_result_message(req.keyword, items)
        if result_msg:
            await telegram.send_message(result_msg)

        # ── 11. Submit ────────────────────────────────────────────────────
        log.info("SUBMIT reqId=%s items=%d publicIp=%s", req.request_id, len(items), public_ip)
        sent = await client.submit(
            request_id=req.request_id,
            items=items,
            image_url=image_url,
            public_ip=public_ip,
            source_name=source_name,
            checked_at=checked_at,
        )
        if not sent:
            log.error("Socket submit failed for reqId=%s", req.request_id)

    except Exception as e:
        log.error("FAILED reqId=%s keyword='%s': %s", req.request_id, req.keyword, e)

    finally:
        if cdp:
            try:
                await cdp.close()
            except Exception:
                pass
        if local_proxy:
            await local_proxy.stop()
        try:
            await adb.clear_proxy()
        except Exception:
            pass
        try:
            await adb.remove_cdp_forward()
        except Exception:
            pass
        if screenshot and os.path.exists(screenshot):
            os.remove(screenshot)


# ── Helpers ───────────────────────────────────────────────────────────────────

def _parse_proxy(proxy_str: str) -> Optional[dict]:
    if not proxy_str:
        return None
    parts = [p.strip() for p in proxy_str.split(":")]
    try:
        if len(parts) == 2:
            return {"host": parts[0], "port": int(parts[1]), "user": None, "pass": None}
        if len(parts) == 4:
            return {"host": parts[0], "port": int(parts[1]), "user": parts[2], "pass": parts[3]}
    except (ValueError, IndexError):
        pass
    return None


def _build_results(items_raw: list) -> list:
    results = []
    seen_domains: set[str] = set()
    for i, item in enumerate(items_raw, start=1):
        url    = item.get("u", "")
        domain = item.get("d", "")
        if not url or not domain:
            continue
        if domain in seen_domains:
            continue
        seen_domains.add(domain)
        results.append({"rank": i, "url": url, "domain": domain, "title": item.get("t", "")})
    return results


def _normalize_domain(domain: str) -> str:
    d = domain.strip().lower()
    for prefix in ("https://", "http://"):
        if d.startswith(prefix):
            d = d[len(prefix):]
    d = d.split("/")[0]
    if d.startswith("www."):
        d = d[4:]
    return d


def _check_duplicate_domain(items: list) -> Optional[str]:
    top = items[:10]
    if len(top) < 5:
        return None
    counts: dict[str, int] = {}
    for r in top:
        nd = _normalize_domain(r["domain"])
        if nd:
            counts[nd] = counts.get(nd, 0) + 1
    if not counts:
        return None
    worst_domain = max(counts, key=lambda k: counts[k])
    worst_count  = counts[worst_domain]
    dup_limit    = 7 if len(top) >= 8 else len(top)
    if worst_count >= dup_limit:
        return f"Submit fail: top trung domain {worst_domain} {worst_count}/{len(top)}"
    if len(top) >= 8 and len(counts) <= 2 and worst_count >= 5:
        return f"Submit fail: top lap bat thuong {worst_domain} {worst_count}/{len(top)}"
    return None


async def _wait_dom(cdp: CDPClient, min_count: int = 1, timeout: float = 30) -> None:
    deadline = asyncio.get_event_loop().time() + timeout
    prev_count = 0
    stable_since: Optional[float] = None

    while True:
        try:
            val = await _safe_eval(cdp, WAIT_READY_JS, timeout=5)
            count = int(val) if val is not None else 0
        except Exception:
            count = 0

        if count >= min_count:
            if min_count <= 1:
                return  # simple load check — done immediately
            if count == prev_count:
                if stable_since is None:
                    stable_since = asyncio.get_event_loop().time()
                elif asyncio.get_event_loop().time() - stable_since >= 1.5:
                    return  # stable for 1.5s with enough elements
            else:
                stable_since = None
            prev_count = count

        if asyncio.get_event_loop().time() >= deadline:
            if count > 0:
                return  # timeout but page has something — try anyway
            raise RuntimeError(f"DOM timeout after {timeout}s (count={count})")

        await asyncio.sleep(DOM_POLL_INTERVAL)


async def _safe_eval(cdp: CDPClient, js: str, timeout: float = 30.0) -> Optional[str]:
    try:
        return await cdp.evaluate(js, timeout=timeout)
    except Exception as e:
        log.debug("evaluate error (ignored): %s", e)
        return None


async def _get_public_ip(
    proxy_info: Optional[dict],
    local_proxy: Optional[LocalProxyServer],
) -> str:
    proxy_url: Optional[str] = None

    if proxy_info:
        if local_proxy and local_proxy.local_port:
            proxy_url = f"http://localhost:{local_proxy.local_port}"
        elif not proxy_info["user"]:
            proxy_url = f"http://{proxy_info['host']}:{proxy_info['port']}"

    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(
                "https://api.ipify.org",
                proxy=proxy_url,
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                return (await resp.text()).strip()
    except Exception as e:
        log.warning("Failed to get public IP: %s", e)
        return ""
