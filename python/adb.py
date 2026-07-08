"""ADB wrapper for device control, proxy management, and CDP port forwarding."""
import asyncio
import logging
import re
import xml.etree.ElementTree as ET
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

    async def reverse_port(self, port: int) -> None:
        """Forward Android 127.0.0.1:port → PC localhost:port via USB (bypasses firewall)."""
        await self._run("reverse", f"tcp:{port}", f"tcp:{port}")
        log.info("ADB reverse: phone:127.0.0.1:%d -> PC:%d", port, port)

    async def remove_reverse_port(self, port: int) -> None:
        await self._run_silent("reverse", "--remove", f"tcp:{port}")

    async def remove_all_reverse(self) -> None:
        await self._run_silent("reverse", "--remove-all")

    async def clear_proxy(self) -> None:
        await self._run_silent("shell", "settings", "delete", "global", "http_proxy")
        log.info("ADB proxy cleared")

    async def forward_cdp(self) -> None:
        await self._run("forward", f"tcp:{self._cdp_port}", "localabstract:chrome_devtools_remote")
        log.info("CDP forwarded to :%d", self._cdp_port)

    async def remove_cdp_forward(self) -> None:
        await self._run_silent("forward", "--remove", f"tcp:{self._cdp_port}")

    async def force_stop_chrome(self) -> None:
        await self._run_silent("shell", "am", "force-stop", "com.android.chrome")
        log.info("Chrome force-stopped")

    async def _tap_ui_element(self, *labels: str) -> bool:
        await self._run_silent("shell", "uiautomator", "dump", "/sdcard/uidump.xml", timeout=10)
        try:
            xml_str = await self._run("shell", "cat", "/sdcard/uidump.xml", timeout=5)
            root = ET.fromstring(xml_str)
        except Exception as e:
            log.warning("UI dump parse failed: %s", e)
            return False
        label_set = set(labels)
        for node in root.iter("node"):
            if node.get("text", "") in label_set or node.get("content-desc", "") in label_set:
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if m:
                    x = (int(m.group(1)) + int(m.group(3))) // 2
                    y = (int(m.group(2)) + int(m.group(4))) // 2
                    await self._run("shell", "input", "tap", str(x), str(y))
                    log.info("Tapped %r at (%d,%d)", node.get("content-desc") or node.get("text"), x, y)
                    return True
        log.warning("UI element not found: %s", labels)
        return False

    async def launch_chrome(self, url: str) -> None:
        await self._run(
            "shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", url, "com.android.chrome",
        )
        await asyncio.sleep(2.5)
        log.info("Chrome launched: %s", url)

    async def clear_data_via_menu(self) -> None:
        """3-dot menu → Xoá dữ liệu duyệt web → Tuỳ chọn khác → tick checkboxes → Xoá dữ liệu."""
        await asyncio.sleep(1.0)

        _MENU = ("Tùy chỉnh và kiểm soát Google Chrome", "More options", "Thêm tùy chọn")
        tapped = await self._tap_ui_element(*_MENU)
        if not tapped:
            await asyncio.sleep(1.5)
            tapped = await self._tap_ui_element(*_MENU)
        if not tapped:
            log.warning("3-dot menu not found — skipping clear data")
            return
        await asyncio.sleep(1.0)

        tapped = await self._tap_ui_element(
            "Xoá dữ liệu duyệt web", "Xóa dữ liệu duyệt web", "Clear browsing data",
        )
        if not tapped:
            log.warning("'Xoá dữ liệu duyệt web' not found — skipping clear data")
            return
        await asyncio.sleep(1.5)

        tapped = await self._tap_ui_element("Tuỳ chọn khác", "Tùy chọn khác", "More options")
        if not tapped:
            log.warning("'Tuỳ chọn khác' not found — skipping full settings")
            return
        await asyncio.sleep(2.0)

        # Set time range to "Mọi lúc" (All time) — tap the dropdown then select
        await self._tap_ui_element(
            "1 giờ qua", "15 phút qua", "24 giờ qua", "7 ngày qua", "4 tuần qua",
            "Phạm vi thời gian", "Time range",
        )
        await asyncio.sleep(1.0)
        await self._tap_ui_element("Từ trước đến nay", "Mọi lúc", "Toàn bộ thời gian", "All time")
        await asyncio.sleep(0.5)

        for label in ("Thẻ", "Mật khẩu đã lưu", "Dữ liệu tự động điền vào biểu mẫu"):
            await self._ensure_checked(label)
            await asyncio.sleep(0.3)

        _CLEAR = ("Xoá dữ liệu", "Xóa dữ liệu", "XOÁ DỮ LIỆU", "XÓA DỮ LIỆU", "Clear data", "CLEAR DATA")
        tapped = await self._tap_ui_element(*_CLEAR)
        await asyncio.sleep(1.0)
        if tapped:
            await self._tap_ui_element(*_CLEAR, "Xoá", "Xóa", "XOÁ", "XÓA", "Clear", "CLEAR")
        await asyncio.sleep(2.0)
        log.info("Browsing data cleared via Chrome menu")

    async def _debug_ui_texts(self) -> None:
        """Log all visible text/content-desc nodes — used to find correct labels."""
        await self._run_silent("shell", "uiautomator", "dump", "/sdcard/uidump.xml", timeout=10)
        try:
            xml_str = await self._run("shell", "cat", "/sdcard/uidump.xml", timeout=5)
            root = ET.fromstring(xml_str)
        except Exception as e:
            log.warning("UI dump parse failed: %s", e)
            return
        seen: list[str] = []
        for node in root.iter("node"):
            t = node.get("text", "")
            d = node.get("content-desc", "")
            if t:
                seen.append(f"text={t!r}")
            if d and d != t:
                seen.append(f"desc={d!r}")
        log.info("UI nodes: %s", " | ".join(seen) if seen else "(empty)")

    async def _ensure_checked(self, *labels: str) -> None:
        """Tap a checkbox only if it is currently unchecked.

        Search order for the checkable node:
        1. The text node itself (if checkable)
        2. A checkable sibling in the same parent row (e.g. CheckBox next to label)
        3. Nearest checkable ancestor (fallback)

        Using sibling-first avoids hitting a container ancestor whose
        checked="false" is static and does not reflect the real visual state.
        """
        await self._run_silent("shell", "uiautomator", "dump", "/sdcard/uidump.xml", timeout=10)
        try:
            xml_str = await self._run("shell", "cat", "/sdcard/uidump.xml", timeout=5)
            root = ET.fromstring(xml_str)
        except Exception as e:
            log.warning("UI dump parse failed: %s", e)
            return

        parent_map: dict = {child: parent for parent in root.iter() for child in parent}

        label_set = set(labels)
        for node in root.iter("node"):
            text_match = (
                node.get("text", "") in label_set
                or node.get("content-desc", "") in label_set
            )
            if not text_match:
                continue

            label = node.get("text") or node.get("content-desc")

            # Log all attributes of the matching node to understand its structure
            log.info(
                "Found node %r: class=%s checkable=%s checked=%s clickable=%s bounds=%s",
                label,
                node.get("class", "?"),
                node.get("checkable", "?"),
                node.get("checked", "?"),
                node.get("clickable", "?"),
                node.get("bounds", "?"),
            )

            # Log sibling attributes too
            parent = parent_map.get(node)
            if parent is not None:
                for sibling in parent:
                    if sibling is not node:
                        log.info(
                            "  sibling: class=%s checkable=%s checked=%s bounds=%s",
                            sibling.get("class", "?"),
                            sibling.get("checkable", "?"),
                            sibling.get("checked", "?"),
                            sibling.get("bounds", "?"),
                        )

            checkable = None

            if node.get("checkable") == "true":
                checkable = node
            else:
                if parent is not None:
                    for sibling in parent:
                        if sibling is not node and sibling.get("checkable") == "true":
                            checkable = sibling
                            break

                if checkable is None:
                    ancestor = parent
                    while ancestor is not None:
                        if ancestor.get("checkable") == "true":
                            checkable = ancestor
                            break
                        ancestor = parent_map.get(ancestor)

            if checkable is not None:
                if checkable.get("checked") == "false":
                    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", checkable.get("bounds", ""))
                    if m:
                        x = (int(m.group(1)) + int(m.group(3))) // 2
                        y = (int(m.group(2)) + int(m.group(4))) // 2
                        await self._run("shell", "input", "tap", str(x), str(y))
                        log.info("Checked: %s", label)
                else:
                    log.info("Already checked: %s", label)
                return

            # Fallback: Chrome custom views expose no checkable node.
            # Walk up ALL ancestors: if any has checked="true" → already ticked → skip.
            # Otherwise find the nearest clickable row and tap it.
            ancestor = parent
            while ancestor is not None:
                log.info(
                    "  ancestor: class=%s checkable=%s checked=%s clickable=%s bounds=%s",
                    ancestor.get("class", "?"), ancestor.get("checkable", "?"),
                    ancestor.get("checked", "?"), ancestor.get("clickable", "?"),
                    ancestor.get("bounds", "?"),
                )
                if ancestor.get("checked") == "true":
                    log.info("Already checked (ancestor state): %r", label)
                    return
                ancestor = parent_map.get(ancestor)

            row = parent
            while row is not None:
                if row.get("clickable") == "true":
                    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", row.get("bounds", ""))
                    if m:
                        x = (int(m.group(1)) + int(m.group(3))) // 2
                        y = (int(m.group(2)) + int(m.group(4))) // 2
                        await self._run("shell", "input", "tap", str(x), str(y))
                        log.info("Tapped row for %r at (%d,%d)", label, x, y)
                    return
                row = parent_map.get(row)

            log.warning("No checkable or clickable row found for %r — skipping", label)
            return

    async def start_screenrecord(self, remote_path: str, bit_rate: int = 2_000_000) -> asyncio.subprocess.Process:
        """Start screenrecord on device. Returns the adb subprocess — call stop_screenrecord() to end it."""
        dir_path = remote_path.rsplit("/", 1)[0]
        await self._run_silent("shell", "mkdir", "-p", dir_path, timeout=5)

        cmd = self._base() + [
            "shell", "screenrecord",
            "--bit-rate", str(bit_rate),
            "--time-limit", "3600",
            remote_path,
        ]
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        await asyncio.sleep(1.0)
        if proc.returncode is not None:
            err = (await proc.stderr.read()).decode().strip()
            raise RuntimeError(f"screenrecord exited immediately: {err}")

        log.info("Screenrecord started -> %s", remote_path)
        return proc

    async def stop_screenrecord(self, proc: asyncio.subprocess.Process) -> None:
        """Send SIGINT to screenrecord on device so it flushes mp4 moov atom, then wait."""
        try:
            pid = (await self._run("shell", "pidof", "screenrecord", timeout=5)).strip()
            if pid:
                await self._run_silent("shell", "kill", "-2", pid, timeout=5)
                log.info("Sent SIGINT to screenrecord pid=%s", pid)
            else:
                log.warning("screenrecord pid not found — may have already exited")
        except Exception as e:
            log.warning("pidof screenrecord failed: %s — trying pkill", e)
            await self._run_silent("shell", "pkill", "-2", "screenrecord", timeout=5)

        await asyncio.sleep(1.5)
        try:
            await asyncio.wait_for(proc.wait(), timeout=10)
            log.info("Screenrecord stopped")
        except asyncio.TimeoutError:
            log.warning("Screenrecord didn't exit — killing adb process")
            proc.kill()

    async def pull_file(self, remote_path: str, local_path: str) -> None:
        await self._run("pull", remote_path, local_path, timeout=60)
        log.info("Pulled %s -> %s", remote_path, local_path)

    async def remove_device_file(self, remote_path: str) -> None:
        await self._run_silent("shell", "rm", "-f", remote_path, timeout=5)

    async def media_scan(self, remote_path: str) -> None:
        """Notify Android MediaScanner so the file appears in Gallery immediately."""
        await self._run_silent(
            "shell", "am", "broadcast",
            "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", f"file://{remote_path}",
            timeout=5,
        )

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

    async def input_tap(self, x: int, y: int) -> None:
        await self._run("shell", "input", "tap", str(x), str(y))
        log.info("ADB tap (%d, %d)", x, y)

    async def input_text(self, text: str) -> None:
        # Android input text uses URL-encoding: %s = space, %% = literal %
        encoded = text.replace("%", "%%").replace(" ", "%s")
        await self._run("shell", "input", "text", encoded, timeout=30)
        log.info("ADB input text: %r", text)

    async def input_keyevent(self, keycode: int) -> None:
        await self._run("shell", "input", "keyevent", str(keycode))
        log.info("ADB keyevent %d", keycode)

    async def get_device_info(self) -> dict:
        manufacturer = await self._run("shell", "getprop", "ro.product.manufacturer")
        model        = await self._run("shell", "getprop", "ro.product.model")
        android_id   = await self._run("shell", "settings", "get", "secure", "android_id")
        return {"manufacturer": manufacturer, "model": model, "android_id": android_id}

