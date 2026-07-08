"""Telegram uploader — Python port of TelegramUploader.kt."""
import asyncio
import logging
import os
from typing import Optional

import aiohttp

from config import BOT_BASE, BOT_TOKEN, CHAT_ID, FILE_BASE

log = logging.getLogger(__name__)

_upload_lock = asyncio.Lock()


def build_result_message(keyword: str, items: list, max_items: int = 10) -> str:
    if not items:
        return ""
    lines = [f"Keyword: {keyword}"]
    for r in items[:max_items]:
        line = f"[{r['rank']}, {r['domain']}, {r['url']}]"
        if len("\n".join(lines + [line])) > 950:
            lines.append("...")
            break
        lines.append(line)
    return "\n".join(lines)


async def upload(file_path: str) -> str:
    if not file_path or not os.path.exists(file_path):
        log.error("File not found: %s", file_path)
        return ""
    async with _upload_lock:
        url = await _do_upload(file_path)
        if not url:
            log.warning("Retrying upload after 3s: %s", file_path)
            await asyncio.sleep(3)
            url = await _do_upload(file_path)
        return url


async def _do_upload(file_path: str) -> str:
    try:
        file_id = await _send_document(file_path)
        if not file_id:
            return ""
        tg_path = await _get_file_path(file_id)
        if not tg_path:
            return ""
        url = f"{FILE_BASE}/{tg_path}"
        log.info("Upload OK -> %s", url)
        return url
    except Exception as e:
        log.error("Upload failed: %s", e)
        return ""


async def _send_document(file_path: str) -> Optional[str]:
    with open(file_path, "rb") as f:
        file_bytes = f.read()
    async with aiohttp.ClientSession() as session:
        data = aiohttp.FormData()
        data.add_field("chat_id", CHAT_ID)
        data.add_field(
            "document", file_bytes,
            filename=os.path.basename(file_path),
            content_type="image/jpeg",
        )
        async with session.post(
            f"{BOT_BASE}/sendDocument",
            data=data,
            timeout=aiohttp.ClientTimeout(total=60),
        ) as resp:
            body = await resp.json(content_type=None)
            if not body.get("ok"):
                log.error("sendDocument failed: %s", body)
                return None
            return body["result"]["document"]["file_id"]


async def _get_file_path(file_id: str) -> Optional[str]:
    async with aiohttp.ClientSession() as session:
        async with session.get(
            f"{BOT_BASE}/getFile",
            params={"file_id": file_id},
            timeout=aiohttp.ClientTimeout(total=10),
        ) as resp:
            body = await resp.json(content_type=None)
            if not body.get("ok"):
                log.error("getFile failed: %s", body)
                return None
            return body["result"]["file_path"]


async def send_video(file_path: str) -> bool:
    """Upload a video file to Telegram chat via sendVideo."""
    if not file_path or not os.path.exists(file_path):
        log.error("Video not found: %s", file_path)
        return False
    try:
        with open(file_path, "rb") as f:
            file_bytes = f.read()
        async with aiohttp.ClientSession() as session:
            data = aiohttp.FormData()
            data.add_field("chat_id", CHAT_ID)
            data.add_field(
                "video", file_bytes,
                filename=os.path.basename(file_path),
                content_type="video/mp4",
            )
            async with session.post(
                f"{BOT_BASE}/sendVideo",
                data=data,
                timeout=aiohttp.ClientTimeout(total=120),
            ) as resp:
                body = await resp.json(content_type=None)
                if not body.get("ok"):
                    log.error("sendVideo failed: %s", body)
                    return False
                log.info("Video sent to Telegram: %s", os.path.basename(file_path))
                return True
    except Exception as e:
        log.error("send_video failed: %s", e)
        return False


async def send_message(text: str) -> bool:
    if not text:
        return False
    try:
        async with aiohttp.ClientSession() as session:
            payload = {"chat_id": CHAT_ID, "text": text, "disable_web_page_preview": True}
            async with session.post(
                f"{BOT_BASE}/sendMessage",
                json=payload,
                timeout=aiohttp.ClientTimeout(total=20),
            ) as resp:
                body = await resp.json(content_type=None)
                return body.get("ok", False)
    except Exception as e:
        log.error("sendMessage failed: %s", e)
        return False
