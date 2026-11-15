package com.swan1127.repland.data.room

import com.swan1127.repland.domain.model.PlanDraft
import com.swan1127.repland.domain.model.PlannedSegment
import com.swan1127.repland.domain.model.SegmentAuthority
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

/** 段权威与稳定身份是编排层的地基：落库、草案编解码任一环丢失都会让撤销和对比认错段。 */
class PlanSegmentAuthorityTest {
    private val day = LocalDate.of(2026, 10, 16)

    @Test fun codec_round_trip_preserves_authority_and_stable_key() {
        val draft = PlanDraft(
            generatedAt = LocalDateTime.of(2026, 10, 16, 8, 0),
            segments = listOf(
                PlannedSegment("pinned", "a", day, 600, 660, authority = SegmentAuthority.USER_PINNED, stableKey = "k1"),
                PlannedSegment("auto:a:${day.toEpochDay()}:780:810", "a", day, 780, 810),
            ),
            pendingTaskIds = emptyList(), unscheduledTasks = emptyList(),
        )
        val decoded = PlanDraftCodec.decode(PlanDraftCodec.encode(draft))
        assertEquals(draft, decoded)
    }

    @Test fun an_old_payload_without_the_new_columns_decodes_as_auto_with_blank_key() {
        val payload = org.json.JSONObject().apply {
            put("version", 1)
            put("generatedAt", "2026-10-16T08:00")
            put("revision", org.json.JSONObject.NULL)
            put("numericProfileVersion", org.json.JSONObject.NULL)
            put("memoryReferenceRevision", org.json.JSONObject.NULL)
            put("manual", false)
            put("order", org.json.JSONArray())
            put("pending", org.json.JSONArray())
            put("segments", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("id", "legacy"); put("task", "a"); put("date", day.toString())
                put("start", 600); put("end", 660); put("locked", false); put("track", "focus")
            }))
            put("unscheduled", org.json.JSONArray())
            put("assessments", org.json.JSONArray())
        }.toString()
        val segment = PlanDraftCodec.decode(payload).segments.single()
        assertEquals(SegmentAuthority.AUTO, segment.authority)
        assertEquals("", segment.stableKey)
    }

    @Test fun an_unknown_authority_value_decodes_as_auto_not_crash() {
        val entity = PlanSegmentEntity("row", "plan", "a", day.toEpochDay(), 600, 660,
            authority = "SOMETHING_ELSE", stableKey = "k1")
        val domain = PlanWithSegments(PlanEntity("plan", 1, true), listOf(entity), emptyList()).toDomain()
        assertEquals(SegmentAuthority.AUTO, domain.segments.single().authority)
        assertEquals("k1", domain.segments.single().stableKey)
    }

    @Test fun entity_mapping_preserves_authority_and_fills_stable_key_from_segment_id() {
        val domain = PlannedSegment("auto:a:${day.toEpochDay()}:600:660", "a", day, 600, 660,
            authority = SegmentAuthority.USER_PINNED, stableKey = "k1")
        val entity = domain.toSegmentEntity("plan", rowId = "row")
        assertEquals("USER_PINNED", entity.authority)
        assertEquals("k1", entity.stableKey)
        assertEquals(PlanWithSegments(PlanEntity("plan", 1, true), listOf(entity), emptyList()).toDomain().segments.single(),
            domain.copy(id = "row"))
    }

    @Test fun a_blank_stable_key_falls_back_to_the_segment_id() {
        val entity = PlannedSegment("auto:a:${day.toEpochDay()}:600:660", "a", day, 600, 660)
            .toSegmentEntity("plan", rowId = "row")
        assertEquals("auto:a:${day.toEpochDay()}:600:660", entity.stableKey)
    }
}
