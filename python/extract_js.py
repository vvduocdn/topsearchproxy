"""Load JS strings directly from the Kotlin source to stay in sync automatically."""
import os
import re

_KT_FILE = os.path.join(
    os.path.dirname(__file__),
    "..", "app", "src", "main", "kotlin",
    "com", "topsearch", "app", "ui", "GoogleSearchJs.kt",
)

with open(_KT_FILE, "r", encoding="utf-8") as _f:
    _SRC = _f.read()


def _extract_val(name: str) -> str:
    """Extract: val NAME = \"\"\"\\n CONTENT \\n\"\"\".trimIndent()"""
    pattern = rf'val {name}\s*=\s*"""\n(.*?)\n"""\s*\.trimIndent\(\)'
    m = re.search(pattern, _SRC, re.DOTALL)
    if not m:
        raise RuntimeError(f"Could not find val {name} in GoogleSearchJs.kt")
    return m.group(1)


ACCEPT_CONSENT_JS              = _extract_val("ACCEPT_CONSENT_JS")
WAIT_READY_JS                  = _extract_val("WAIT_READY_JS")
EXTRACT_JS                     = _extract_val("EXTRACT_JS")
EXTRACT_HEADINGS_IN_IMAGE_ORDER_JS = _extract_val("EXTRACT_HEADINGS_IN_IMAGE_ORDER_JS")


def build_search_js(keyword: str) -> str:
    """Port of GoogleSearchJs.buildSearchJs — escape keyword for JS single-quoted string."""
    escaped = keyword.replace("\\", "\\\\").replace("'", "\\'")
    return (
        "(function() {"
        "\n    var q = document.querySelector('textarea[name=\"q\"]')"
        " || document.querySelector('input[name=\"q\"]');"
        "\n    if (!q) return false;"
        f"\n    q.value = '{escaped}';"
        "\n    var form = q.form || q.closest('form');"
        "\n    if (form) { form.submit(); return true; }"
        "\n    return false;"
        "\n})()"
    )
