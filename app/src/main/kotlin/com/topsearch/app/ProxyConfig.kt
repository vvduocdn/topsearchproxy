package com.topsearch.app

/**
 * Cấu hình proxy residential IPFoxy theo tỉnh/thành.
 * Format: "host:port:username:password"
 *
 * Lấy credentials tại: IPFoxy → Generate Proxy → chọn State → Generate
 */
object CityProxies {

    // ── TP. Hồ Chí Minh ──────────────────────────────────────────────────────
    const val HCM = "gate.ipfoxy.io:58688:" +
        "customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775108834_10004-m-1:" +
        "w4vZUnJcYz9f3aC"

    // ── Hà Nội ────────────────────────────────────────────────────────────────
    const val HN = "gate.ipfoxy.io:58688:" +
        "customer-pX4RmmaNy9-cc-VN-st-Hanoi-city-Hanoi-sessid-1775108858_10005-m-1:" +
        "w4vZUnJcYz9f3aC"

    // ── Đà Nẵng ───────────────────────────────────────────────────────────────
    const val DN = "gate.ipfoxy.io:58688:" +
        "customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775108873_10006-m-1:" +
        "w4vZUnJcYz9f3aC"
}
