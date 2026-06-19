package com.topsearch.app

/**
 * Cấu hình proxy residential IPFoxy theo tỉnh/thành.
 * Format: "host:port:username:password"
 *
 * Lấy credentials tại: IPFoxy → Generate Proxy → chọn State → Generate
 */
object CityProxies {

    // ── TP. Hồ Chí Minh — pool 50 sessid, random mỗi lần để tránh CAPTCHA ──
    private val HCM_POOL = listOf(
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10000-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10001-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10002-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10003-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10004-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10005-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10006-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10007-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10008-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10009-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10010-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10011-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10012-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10013-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10014-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10015-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10016-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10017-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10018-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10019-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10020-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10021-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10022-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10023-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10024-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10025-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10026-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10027-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10028-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10029-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10030-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10031-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10032-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10033-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10034-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10035-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10036-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10037-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10038-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10039-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10040-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10041-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10042-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10043-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10044-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10045-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10046-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10047-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10048-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-HoChiMinh-city-HoChiMinhCity-sessid-1775114856_10049-m-1:w4vZUnJcYz9f3aC",
    )

    /** Lấy ngẫu nhiên 1 proxy HCM từ pool 50 sessid */
    val HCM get() = HCM_POOL.random()
    val HCM_POOL_PUBLIC: List<String> get() = HCM_POOL

    // ── Hà Nội ────────────────────────────────────────────────────────────────
    private val HN_POOL = listOf(
        "117.5.220.204:33978:lnjgv_itweb:dqzFqlTn",
    )

    val HN get() = HN_POOL.random()
    val HN_POOL_PUBLIC: List<String> get() = HN_POOL

    // ── Đà Nẵng — pool 50 sessid ──────────────────────────────────────────────
    private val DN_POOL = listOf(
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10100-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10101-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10102-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10103-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10104-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10105-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10106-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10107-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10108-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10109-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10110-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10111-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10112-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10113-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10114-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10115-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10116-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10117-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10118-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10119-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10120-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10121-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10122-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10123-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10124-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10125-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10126-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10127-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10128-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10129-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10130-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10131-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10132-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10133-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10134-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10135-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10136-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10137-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10138-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10139-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10140-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10141-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10142-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10143-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10144-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10145-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10146-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10147-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10148-m-1:w4vZUnJcYz9f3aC",
        "gate.ipfoxy.io:58688:customer-pX4RmmaNy9-cc-VN-st-Danang-city-Danang-sessid-1775114899_10149-m-1:w4vZUnJcYz9f3aC",
    )

    val DN get() = DN_POOL.random()
    val DN_POOL_PUBLIC: List<String> get() = DN_POOL
}
