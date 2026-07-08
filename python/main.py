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
    # screenrecord has a hard 180-second limit per run.  The watchdog below
    # restarts it automatically so a long batch is captured across multiple
    # segments, all sent to Telegram at the end.
    segments: list[str] = []
    _seg_idx = 0
    _safe_id = ""
    _cur_proc: Optional[asyncio.subprocess.Process] = None
    _stop_rec = asyncio.Event()
    watchdog_task: Optional[asyncio.Task] = None

    async def _watchdog() -> None:
        nonlocal _seg_idx, _cur_proc
        while not _stop_rec.is_set():
            remote = f"/sdcard/DCIM/rec_{_safe_id}_{_seg_idx:02d}.mp4"
            try:
                proc = await adb.start_screenrecord(remote)
                _cur_proc = proc
                segments.append(remote)
                log.info("Recording segment %02d started: %s", _seg_idx, remote)
                _seg_idx += 1
                await proc.wait()   # returns when screenrecord hits 3-min limit or SIGINT
                _cur_proc = None
                log.info("Recording segment ended: %s", remote)
            except asyncio.CancelledError:
                break
            except Exception as e:
                log.warning("Recording segment error: %s — retrying in 2s", e)
                _cur_proc = None
                await asyncio.sleep(2)

    while True:
        req = await _queue.get()

        # Start recording watchdog once at the beginning of a new batch
        if watchdog_task is None:
            _safe_id = "".join(c if c.isalnum() else "_" for c in req.request_id[:8])
            _stop_rec.clear()
            _seg_idx = 0
            segments.clear()
            watchdog_task = asyncio.create_task(_watchdog())
            log.info("Batch recording watchdog started (id=%s)", _safe_id)

        try:
            await process(req, source_name, client)
        except Exception as e:
            log.error("Unhandled error in worker for reqId=%s: %s", req.request_id, e)
        finally:
            _queue.task_done()

        # All keywords done — finalize and send all segments
        if _queue.empty() and watchdog_task is not None:
            _stop_rec.set()

            # Stop the current segment gracefully (SIGINT → moov atom flushed)
            cur = _cur_proc
            _cur_proc = None
            if cur is not None and cur.returncode is None:
                try:
                    await adb.stop_screenrecord(cur)
                except Exception as e:
                    log.warning("Stop recording segment error: %s", e)

            # Cancel watchdog (prevents race-condition new segment from starting)
            watchdog_task.cancel()
            try:
                await watchdog_task
            except (asyncio.CancelledError, Exception):
                pass
            watchdog_task = None

            # Safety: kill any screenrecord still running on device after race window
            await adb._run_silent("shell", "pkill", "-2", "screenrecord", timeout=5)
            await asyncio.sleep(1.5)

            # Pull all segments from device to PC
            log.info("Batch done — pulling %d segment(s) from device", len(segments))
            local_segs: list[str] = []
            for i, remote in enumerate(list(segments)):
                _fd, local = tempfile.mkstemp(suffix=".mp4")
                os.close(_fd)
                try:
                    size = await adb._run("shell", "stat", "-c", "%s", remote, timeout=5)
                    log.info("Segment %d/%d on device: %s bytes — %s", i + 1, len(segments), size, remote)
                    await adb.pull_file(remote, local)
                    await adb.media_scan(remote)
                    local_segs.append(local)
                except Exception as e:
                    log.warning("Failed to pull segment %d (%s): %s", i + 1, remote, e)
                    if os.path.exists(local):
                        os.remove(local)
            segments.clear()

            if not local_segs:
                log.warning("No segments pulled — nothing to send")
            elif len(local_segs) == 1:
                try:
                    await telegram.send_video(local_segs[0])
                except Exception as e:
                    log.warning("Failed to send video: %s", e)
                finally:
                    os.remove(local_segs[0])
            else:
                _fd, merged = tempfile.mkstemp(suffix=".mp4")
                os.close(_fd)
                try:
                    await _concat_segments(local_segs, merged)
                    await telegram.send_video(merged)
                except Exception as e:
                    log.warning("Failed to concat/send merged video: %s", e)
                finally:
                    if os.path.exists(merged):
                        os.remove(merged)
                    for seg in local_segs:
                        if os.path.exists(seg):
                            os.remove(seg)


async def _concat_segments(local_paths: list[str], output: str) -> None:
    """Merge MP4 segments into one file using ffmpeg copy (no re-encode)."""
    list_file = output + ".txt"
    with open(list_file, "w") as f:
        for p in local_paths:
            f.write(f"file '{p}'\n")
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-y", "-f", "concat", "-safe", "0",
            "-i", list_file, "-c", "copy", output,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await asyncio.wait_for(proc.communicate(), timeout=300)
        if proc.returncode != 0:
            raise RuntimeError(f"ffmpeg: {stderr.decode().strip()[-300:]}")
        log.info("Merged %d segments → %s (%d bytes)", len(local_paths), output, os.path.getsize(output))
    finally:
        if os.path.exists(list_file):
            os.remove(list_file)


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
