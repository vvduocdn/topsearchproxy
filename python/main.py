"""Entry point: connect to SignalR, queue incoming keywords, process them one at a time."""
import asyncio
import logging
import os
import signal
import sys
import tempfile
from typing import List, Optional

from adb import ADB
from config import ADB_SERIAL, CDP_LOCAL_PORT, SOCKET_URL
from processor import process
from signalr import SignalRClient, SocketRequest
import telegram

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-7s %(name)s — %(message)s",
    datefmt="%H:%M:%S",
    stream=sys.stdout,
)
log = logging.getLogger(__name__)

_queue: asyncio.Queue[SocketRequest] = asyncio.Queue()


async def _worker(source_name: str, client: SignalRClient, adb: ADB) -> None:
    rec_proc:   Optional[asyncio.subprocess.Process] = None
    rec_remote: Optional[str]                        = None

    while True:
        req = await _queue.get()

        # Start recording once at the beginning of a new batch
        if rec_proc is None:
            _safe_id = "".join(c if c.isalnum() else "_" for c in req.request_id[:8])
            rec_remote = f"/sdcard/DCIM/rec_{_safe_id}.mp4"
            try:
                rec_proc = await adb.start_screenrecord(rec_remote)
                log.info("Batch recording started: %s", rec_remote)
            except Exception as e:
                log.warning("Screenrecord start failed (continuing without): %s", e)
                rec_proc = None
                rec_remote = None

        try:
            await process(req, source_name, client)
        except Exception as e:
            log.error("Unhandled error in worker for reqId=%s: %s", req.request_id, e)
        finally:
            _queue.task_done()

        # When queue is empty (all keywords done), finalize and send the recording
        if _queue.empty() and rec_proc and rec_remote:
            _rec_proc   = rec_proc
            _rec_remote = rec_remote
            rec_proc    = None
            rec_remote  = None
            try:
                await adb.stop_screenrecord(_rec_proc)
                try:
                    size = await adb._run("shell", "stat", "-c", "%s", _rec_remote, timeout=5)
                    log.info("Batch recording on device: %s bytes", size)
                except Exception:
                    log.warning("Recording file not found on device: %s", _rec_remote)
                _fd, rec_local = tempfile.mkstemp(suffix=".mp4")
                os.close(_fd)
                await adb.pull_file(_rec_remote, rec_local)
                await adb.media_scan(_rec_remote)
                await telegram.send_video(rec_local)
                os.remove(rec_local)
            except Exception as e:
                log.warning("Batch recording finalize failed: %s", e)


async def _on_batch(batch: List[SocketRequest]) -> None:
    for req in batch:
        await _queue.put(req)
        log.info("Queued reqId=%s keyword='%s' (queue size=%d)",
                 req.request_id, req.keyword, _queue.qsize())


async def main() -> None:
    adb = ADB(serial=ADB_SERIAL, cdp_port=CDP_LOCAL_PORT)

    loop = asyncio.get_running_loop()
    stop_event = asyncio.Event()

    def _on_signal():
        log.info("Shutdown signal received — cleaning up…")
        stop_event.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, _on_signal)

    # Clear any leftover proxy/tunnel from a previous crashed run
    try:
        await adb.clear_proxy()
        await adb.remove_cdp_forward()
        await adb.remove_all_reverse()
        log.info("ADB state cleared on startup")
    except Exception as e:
        log.warning("Startup cleanup error: %s", e)

    log.info("Fetching device info via ADB…")
    try:
        info = await adb.get_device_info()
        source_name = (
            f"{info['manufacturer']} {info['model']} ({info['android_id'][:8]})"
        )
    except Exception as e:
        log.warning("Could not get device info: %s — using fallback source name", e)
        source_name = "Unknown Device"

    log.info("Source name: %s", source_name)

    client = SignalRClient(url=SOCKET_URL, on_batch=_on_batch)

    worker_task = asyncio.create_task(_worker(source_name, client, adb))
    signalr_task = asyncio.create_task(client.run_forever())

    await stop_event.wait()

    signalr_task.cancel()
    worker_task.cancel()
    for task in (signalr_task, worker_task):
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass

    # Always clear proxy on exit so Android has internet
    log.info("Clearing ADB proxy…")
    try:
        await adb.clear_proxy()
        await adb.remove_cdp_forward()
    except Exception as e:
        log.warning("Cleanup error: %s", e)

    log.info("Shutdown complete")


if __name__ == "__main__":
    asyncio.run(main())
