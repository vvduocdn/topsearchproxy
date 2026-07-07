"""ADB wrapper for device control, proxy management, and CDP port forwarding."""
import asyncio
import logging
import socket
from typing import Optional

log = logging.getLogger(__name__)


class ADB:
    def __init__(self, serial: Optional[str] = None, cdp_port: int = 9222):
        self._serial = serial
        self._cdp_port = cdp_port

    def _base(self) -> list[str]:
        return ["adb", "-s", self._serial] if self._serial else ["adb"]

    async def _run(self, *args: str, timeout: int = 10) -> str:
        cmd = self._base() + list(args)
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
        if proc.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {stderr.decode().strip()}")
        return stdout.decode().strip()

    async def _run_silent(self, *args: str, timeout: int = 10) -> None:
        try:
            await self._run(*args, timeout=timeout)
        except Exception as e:
            log.debug("adb %s (ignored): %s", " ".join(args), e)

    async def set_proxy(self, host: str, port: int) -> None:
        await self._run("shell", "settings", "put", "global", "http_proxy", f"{host}:{port}")
        log.info("ADB proxy set: %s:%d", host, port)

    async def clear_proxy(self) -> None:
        # ":0" is the Android convention for "no proxy"
        await self._run("shell", "settings", "put", "global", "http_proxy", ":0")
        log.info("ADB proxy cleared")

    async def forward_cdp(self) -> None:
        await self._run("forward", f"tcp:{self._cdp_port}", "localabstract:chrome_devtools_remote")
        log.info("CDP forwarded to :%d", self._cdp_port)

    async def remove_cdp_forward(self) -> None:
        await self._run_silent("forward", "--remove", f"tcp:{self._cdp_port}")

    async def force_stop_chrome(self) -> None:
        await self._run_silent("shell", "am", "force-stop", "com.android.chrome")
        log.info("Chrome force-stopped")

    async def launch_chrome(self, url: str) -> None:
        await self._run(
            "shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", url, "com.android.chrome",
        )
        log.info("Chrome launched: %s", url)

    async def take_screenshot(self, output_path: str) -> None:
        cmd = self._base() + ["exec-out", "screencap", "-p"]
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=20)
        if proc.returncode != 0:
            raise RuntimeError(f"screencap failed: {stderr.decode().strip()}")
        with open(output_path, "wb") as f:
            f.write(stdout)
        log.info("Screenshot saved: %s (%d bytes)", output_path, len(stdout))

    async def get_device_info(self) -> dict:
        manufacturer = await self._run("shell", "getprop", "ro.product.manufacturer")
        model        = await self._run("shell", "getprop", "ro.product.model")
        android_id   = await self._run("shell", "settings", "get", "secure", "android_id")
        return {"manufacturer": manufacturer, "model": model, "android_id": android_id}

    def get_local_ip(self) -> str:
        """Return this PC's LAN IP (visible to the Android device)."""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return "127.0.0.1"
