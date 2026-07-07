import os

SOCKET_URL = os.getenv(
    "SOCKET_URL",
    "wss://baotop-api.toolok.live/hubs/mobile-check?secret=ds-socket-9k3m7x2q5w8e1r4t6y0u",
)

BOT_TOKEN = "8642171462:AAEq1woSJt7G7P-VfBPe6KDdzH1xSFQd5oo"
CHAT_ID   = "-1003983539533"
FILE_BASE = f"https://api.telegram.org/file/bot{BOT_TOKEN}"
BOT_BASE  = f"https://api.telegram.org/bot{BOT_TOKEN}"

ADB_SERIAL       = os.getenv("ADB_SERIAL")    # None = first connected device
CDP_LOCAL_PORT   = 9222
GOOGLE_URL       = "https://www.google.com"
SEARCH_TIMEOUT   = 30.0   # seconds to wait for search results
DOM_POLL_INTERVAL = 0.5   # seconds between DOM readiness polls
