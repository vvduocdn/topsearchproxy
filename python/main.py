"""Entry point: connect to SignalR, queue incoming keywords, process them one at a time."""
import asyncio
import logging
import sys
from typing import List

from adb import ADB
from config import ADB_SERIAL, CDP_LOCAL_PORT, SOCKET_URL
from processor import process
from signalr import SignalRClient, SocketRequest

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-7s %(name)s — %(message)s",
    datefmt="%H:%M:%S",
    stream=sys.stdout,
)
log = logging.getLogger(__name__)

_queue: asyncio.Queue[SocketRequest] = asyncio.Queue()


async def _worker(source_name: str, client: SignalRClient) -> None:
    while True:
        req = await _queue.get()
        try:
            await process(req, source_name, client)
        except Exception as e:
            log.error("Unhandled error in worker for reqId=%s: %s", req.request_id, e)
        finally:
            _queue.task_done()


async def _on_batch(batch: List[SocketRequest]) -> None:
    for req in batch:
        await _queue.put(req)
        log.info("Queued reqId=%s keyword='%s' (queue size=%d)",
                 req.request_id, req.keyword, _queue.qsize())


async def main() -> None:
    adb = ADB(serial=ADB_SERIAL, cdp_port=CDP_LOCAL_PORT)

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

    worker_task = asyncio.create_task(_worker(source_name, client))

    try:
        await client.run_forever()
    finally:
        worker_task.cancel()
        try:
            await worker_task
        except asyncio.CancelledError:
            pass


if __name__ == "__main__":
    asyncio.run(main())
