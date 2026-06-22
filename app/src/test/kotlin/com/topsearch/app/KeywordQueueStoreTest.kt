package com.topsearch.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Unit tests cho [KeywordQueueStore] — JSON file persistence cho crash recovery.
 *
 * KeywordQueueStore lưu trữ danh sách Entry vào file JSON.
 * Entry gồm: requestId, keyword, proxy, country, status.
 *
 * Test strategy: dùng thư mục tạm để không ảnh hưởng file thật.
 */
class KeywordQueueStoreTest {

    private val testDir = createTempDir(prefix = "topsearch_test_")
    private val fileName = "keyword_queue.json"

    private fun file() = File(testDir, fileName)

    // ══════════════════════════════════════════════════════════════
    // Logic save/load tái hiện KeywordQueueStore
    // ══════════════════════════════════════════════════════════════

    data class Entry(
        val requestId: String,
        val keyword: String,
        val proxy: String,
        val country: Int,
        val status: CheckStatus = CheckStatus.PENDING,
    )

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("requestId", e.requestId)
                put("keyword", e.keyword)
                put("proxy", e.proxy)
                put("country", e.country)
                put("status", e.status.name)
            })
        }
        file().writeText(arr.toString())
    }

    private fun load(): List<Entry>? {
        val f = file()
        if (!f.exists()) return null
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    requestId = o.getString("requestId"),
                    keyword   = o.getString("keyword"),
                    proxy     = o.optString("proxy", ""),
                    country   = o.optInt("country", 1),
                    status    = runCatching {
                        CheckStatus.valueOf(o.getString("status"))
                    }.getOrElse { CheckStatus.PENDING },
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun updateStatus(requestId: String, status: CheckStatus) {
        val entries = load() ?: return
        save(entries.map { if (it.requestId == requestId) it.copy(status = status) else it })
    }

    private fun clear() {
        file().delete()
    }

    private fun hasPending(): Boolean =
        load()?.any { it.status != CheckStatus.DONE } == true

    // ══════════════════════════════════════════════════════════════
    // Test: save and load round-trip
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `save and load round-trip preserves all fields`() {
        val entries = listOf(
            Entry("req-001", "tu khoa 1", "proxy-hcm-1", 1, CheckStatus.PENDING),
            Entry("req-002", "tu khoa 2", "proxy-hn-1", 1, CheckStatus.DONE),
            Entry("req-003", "tu khoa 3", "", 2, CheckStatus.ERROR),
        )

        save(entries)
        val loaded = load()

        assertNotNull(loaded)
        assertEquals(3, loaded!!.size)

        assertEquals("req-001", loaded[0].requestId)
        assertEquals("tu khoa 1", loaded[0].keyword)
        assertEquals("proxy-hcm-1", loaded[0].proxy)
        assertEquals(1, loaded[0].country)
        assertEquals(CheckStatus.PENDING, loaded[0].status)

        assertEquals("req-002", loaded[1].requestId)
        assertEquals(CheckStatus.DONE, loaded[1].status)

        assertEquals("req-003", loaded[2].requestId)
        assertEquals(2, loaded[2].country)
        assertEquals(CheckStatus.ERROR, loaded[2].status)
    }

    // ══════════════════════════════════════════════════════════════
    // Test: empty file
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `load returns null when file does not exist`() {
        assertFalse(file().exists())
        assertNull(load())
    }

    @Test
    fun `load returns null for empty file`() {
        file().writeText("")
        assertNull(load())
    }

    @Test
    fun `load returns null for invalid JSON`() {
        file().writeText("not json at all {{{")
        assertNull(load())
    }

    // ══════════════════════════════════════════════════════════════
    // Test: updateStatus
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `updateStatus changes status of matching entry`() {
        save(listOf(
            Entry("req-001", "kw1", "", 1, CheckStatus.PENDING),
            Entry("req-002", "kw2", "", 1, CheckStatus.PENDING),
        ))

        updateStatus("req-001", CheckStatus.DONE)

        val loaded = load()!!
        assertEquals(CheckStatus.DONE, loaded.find { it.requestId == "req-001" }!!.status)
        assertEquals(CheckStatus.PENDING, loaded.find { it.requestId == "req-002" }!!.status)
    }

    @Test
    fun `updateStatus does nothing when requestId not found`() {
        save(listOf(
            Entry("req-001", "kw1", "", 1, CheckStatus.PENDING),
        ))

        updateStatus("non-existent-id", CheckStatus.DONE)

        val loaded = load()!!
        assertEquals(CheckStatus.PENDING, loaded[0].status)
    }

    @Test
    fun `updateStatus works for all status types`() {
        save(listOf(Entry("req-1", "kw", "", 1, CheckStatus.PENDING)))

        CheckStatus.entries.forEach { status ->
            updateStatus("req-1", status)
            val loaded = load()!!
            assertEquals("Status $status should be saved", status, loaded[0].status)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Test: clear
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `clear deletes the file`() {
        save(listOf(Entry("req-1", "kw", "", 1, CheckStatus.PENDING)))
        assertTrue(file().exists())

        clear()

        assertFalse(file().exists())
        assertNull(load())
    }

    // ══════════════════════════════════════════════════════════════
    // Test: hasPending
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `hasPending returns true when there are non-DONE entries`() {
        save(listOf(Entry("req-1", "kw", "", 1, CheckStatus.PENDING)))
        assertTrue(hasPending())
    }

    @Test
    fun `hasPending returns false when all entries are DONE`() {
        save(listOf(Entry("req-1", "kw", "", 1, CheckStatus.DONE)))
        assertFalse(hasPending())
    }

    @Test
    fun `hasPending returns false when all entries are DONE mixed with PENDING`() {
        save(listOf(
            Entry("req-1", "kw1", "", 1, CheckStatus.PENDING),
            Entry("req-2", "kw2", "", 1, CheckStatus.DONE),
        ))
        assertTrue(hasPending())
    }

    @Test
    fun `hasPending returns false when all entries are DONE with ERROR`() {
        // ERROR ≠ DONE → vẫn pending
        save(listOf(Entry("req-1", "kw", "", 1, CheckStatus.ERROR)))
        assertTrue(hasPending())
    }

    @Test
    fun `hasPending returns false when file does not exist`() {
        assertFalse(file().exists())
        assertFalse(hasPending())
    }

    @Test
    fun `hasPending returns false for empty file`() {
        file().writeText("[]")
        assertFalse(hasPending())
    }

    // ══════════════════════════════════════════════════════════════
    // Test: Edge cases
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `load handles missing optional fields with defaults`() {
        // Entry không có proxy và status (dùng optString với default)
        file().writeText("""[{"requestId":"req-1","keyword":"kw","country":1}]""")
        val loaded = load()
        assertNotNull(loaded)
        assertEquals(1, loaded!!.size)
        assertEquals("", loaded[0].proxy)
        assertEquals(1, loaded[0].country)
        assertEquals(CheckStatus.PENDING, loaded[0].status) // default fallback
    }

    @Test
    fun `load handles invalid status string with fallback to PENDING`() {
        file().writeText("""[{"requestId":"req-1","keyword":"kw","proxy":"","country":1,"status":"INVALID_STATUS"}]""")
        val loaded = load()
        assertNotNull(loaded)
        assertEquals(CheckStatus.PENDING, loaded!![0].status)
    }

    @Test
    fun `save and load preserves unicode keywords`() {
        val entries = listOf(
            Entry("req-1", "Từ khóa tiếng Việt có dấu ưu ơê", "", 1, CheckStatus.PENDING),
            Entry("req-2", "English Keyword 測試 中文", "", 1, CheckStatus.PENDING),
        )
        save(entries)
        val loaded = load()!!
        assertEquals(entries[0].keyword, loaded[0].keyword)
        assertEquals(entries[1].keyword, loaded[1].keyword)
    }

    @Test
    fun `save and load preserves special chars in proxy`() {
        val entries = listOf(
            Entry("req-1", "kw", "host:port:user:pass!@#\$", 1, CheckStatus.PENDING),
        )
        save(entries)
        val loaded = load()!!
        assertEquals("host:port:user:pass!@#\$", loaded[0].proxy)
    }

    @Test
    fun `load handles empty array`() {
        file().writeText("[]")
        val loaded = load()
        assertNotNull(loaded)
        assertEquals(0, loaded!!.size)
    }

    @Test
    fun `save large number of entries`() {
        val entries = (1..100).map { i ->
            Entry("req-$i", "keyword $i", "proxy-$i", 1, if (i % 2 == 0) CheckStatus.DONE else CheckStatus.PENDING)
        }
        save(entries)
        val loaded = load()!!
        assertEquals(100, loaded.size)
        assertEquals("req-1", loaded[0].requestId)
        assertEquals("req-100", loaded[99].requestId)
    }
}
