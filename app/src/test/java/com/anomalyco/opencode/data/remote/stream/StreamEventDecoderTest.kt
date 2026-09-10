package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.domain.model.PermissionRequest
import com.anomalyco.opencode.domain.model.PermissionStatus
import com.anomalyco.opencode.domain.model.QuestionRequest
import com.anomalyco.opencode.domain.model.QuestionStatus
import com.anomalyco.opencode.domain.model.StreamEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-frame -> domain event translation for every supported
 * `session.next.*` kind, plus the resilience contract:
 * unknown types degrade to [StreamEvent.Unknown],
 * malformed frames are dropped.
 */
class StreamEventDecoderTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val decoder = StreamEventDecoder(json)

    @Test
    fun `decodes text delta`() {
        val raw = """{"type":"session.next.text.delta","properties":{"sessionID":"s1","partID":"p1","delta":"Hel"}}"""
        assertEquals(StreamEvent.TextDelta("s1", "p1", "Hel"), decoder.decode(raw))
    }

    @Test
    fun `decodes reasoning delta`() {
        val raw = """{"type":"session.next.reasoning.delta","properties":{"sessionID":"s1","partID":"p2","delta":"think..."}}"""
        assertEquals(StreamEvent.ReasoningDelta("s1", "p2", "think..."), decoder.decode(raw))
    }

    @Test
    fun `decodes tool called with args nested in part state`() {
        val raw = """
            {"type":"session.next.tool.called","properties":{
              "sessionID":"s1",
              "part":{"id":"c1","callID":"c1","tool":"edit","state":{"status":"running","input":{"path":"a.kt"}}}}
            }
        """.trimIndent()
        assertEquals(
            StreamEvent.ToolCalled("s1", "c1", "edit", """{"path":"a.kt"}"""),
            decoder.decode(raw),
        )
    }

    @Test
    fun `decodes flat tool updated`() {
        val raw = """{"type":"session.next.tool.updated","properties":{"sessionID":"s1","callID":"c1","status":"running"}}"""
        assertEquals(
            StreamEvent.ToolUpdated("s1", "c1", null, com.anomalyco.opencode.domain.model.ToolStatus.RUNNING, null),
            decoder.decode(raw),
        )
    }

    @Test
    fun `decodes tool finished with output`() {
        val raw = """{"type":"session.next.tool.finished","properties":{"sessionID":"s1","callID":"c1","status":"completed","output":"ok"}}"""
        assertEquals(
            StreamEvent.ToolFinished("s1", "c1", com.anomalyco.opencode.domain.model.ToolStatus.COMPLETED, "ok"),
            decoder.decode(raw),
        )
    }

    @Test
    fun `decodes step started and finished`() {
        val started = """{"type":"session.next.step.started","properties":{"sessionID":"s1","id":"st1","description":"calling tool"}}"""
        val finished = """{"type":"session.next.step.finished","properties":{"sessionID":"s1","id":"st1","title":"done"}}"""
        assertEquals(StreamEvent.StepStarted("s1", "st1", "calling tool"), decoder.decode(started))
        assertEquals(StreamEvent.StepFinished("s1", "st1", "done"), decoder.decode(finished))
    }

    @Test
    fun `decodes message updated`() {
        val raw = """{"type":"message.updated","properties":{"sessionID":"s1","messageID":"m9"}}"""
        assertEquals(StreamEvent.MessageUpdated("s1", "m9"), decoder.decode(raw))
    }

    @Test
    fun `falls back to envelope when properties are omitted`() {
        val raw = """{"type":"session.idle","sessionID":"s9"}"""
        assertEquals(StreamEvent.SessionIdle("s9"), decoder.decode(raw))
    }

    @Test
    fun `decodes session error without session id`() {
        val raw = """{"type":"session.error","properties":{"message":"boom"}}"""
        assertEquals(StreamEvent.SessionError(null, "boom"), decoder.decode(raw))
    }

    @Test
    fun `future event types become Unknown instead of crashing`() {
        val raw = """{"type":"session.next.quantum.leap","properties":{"whatever":[1,2,3]}}"""
        assertEquals(StreamEvent.Unknown("session.next.quantum.leap"), decoder.decode(raw))
    }

    @Test
    fun `tolerates unknown fields inside known events`() {
        val raw = """{"type":"session.idle","properties":{"sessionID":"s","weird":{"a":[1]}}}"""
        assertEquals(StreamEvent.SessionIdle("s"), decoder.decode(raw))
    }

    @Test
    fun `drops malformed, blank and type-less frames`() {
        assertNull(decoder.decode("not json at all"))
        assertNull(decoder.decode("   "))
        assertNull(decoder.decode("""{"foo":1}"""))
    }

    @Test
    fun `decodeAll handles batched array frames`() {
        val raw = """[
            {"type":"session.idle","properties":{"sessionID":"a"}},
            {"type":"session.next.text.delta","properties":{"sessionID":"b","partID":"p","delta":"x"}}
        ]""".trimIndent()
        assertEquals(
            listOf(
                StreamEvent.SessionIdle("a"),
                StreamEvent.TextDelta("b", "p", "x"),
            ),
            decoder.decodeAll(raw),
        )
    }

    // ---- permission / question interactive events --------------------------

    @Test
    fun `decodes a bash permission request with command`() {
        val raw = """
            {"type":"permission.asked","properties":{
              "id":"per-7","sessionID":"s1","permission":"bash","description":"Run tests",
              "metadata":{"command":"./gradlew test"}
            }}
        """.trimIndent()
        assertEquals(
            StreamEvent.PermissionAsked(
                "s1",
                PermissionRequest(
                    requestId = "per-7",
                    sessionId = "s1",
                    type = "bash",
                    description = "Run tests",
                    command = "./gradlew test",
                ),
            ),
            decoder.decode(raw),
        )
    }

    @Test
    fun `decodes an edit permission falling back to patterns for the path`() {
        val raw = """
            {"type":"session.next.permission.asked","properties":{
              "requestID":"per-8","sessionID":"s1","permission":"edit",
              "patterns":["src/Main.kt"]
            }}
        """.trimIndent()
        val event = decoder.decode(raw) as StreamEvent.PermissionAsked
        assertEquals("per-8", event.request.requestId)
        assertEquals("s1", event.sessionId)
        assertEquals("edit", event.request.type)
        assertEquals("src/Main.kt", event.request.path)
        assertEquals(PermissionStatus.PENDING, event.request.status)
    }

    @Test
    fun `decodes a question with labelled options and flags`() {
        val raw = """
            {"type":"question.asked","properties":{
              "id":"q-1","sessionID":"s1",
              "questions":[{"question":"Which framework?","options":[{"label":"Compose","hint":"x"},{"label":"Views"}],"multiple":true,"custom":false}]
            }}
        """.trimIndent()
        assertEquals(
            StreamEvent.QuestionAsked(
                "s1",
                QuestionRequest(
                    questionId = "q-1",
                    sessionId = "s1",
                    text = "Which framework?",
                    options = listOf("Compose", "Views"),
                    allowCustomAnswer = false,
                    multiple = true,
                ),
            ),
            decoder.decode(raw),
        )
    }

    @Test
    fun `decodes a flat question with string options and free-text default`() {
        val raw = """
            {"type":"question.asked","properties":{
              "id":"q-2","sessionID":"s1","question":"Pick one","options":["a","b"]
            }}
        """.trimIndent()
        val event = decoder.decode(raw) as StreamEvent.QuestionAsked
        assertEquals("Pick one", event.request.text)
        assertEquals(listOf("a", "b"), event.request.options)
        assertTrue(event.request.allowCustomAnswer)
        assertEquals(QuestionStatus.PENDING, event.request.status)
    }

    @Test
    fun `permission and question frames with unknown extra fields still decode`() {
        val permission = decoder.decode(
            """{"type":"permission.asked","properties":{"id":"p","sessionID":"s","permission":"edit","future":true}}""",
        )
        assertTrue(permission is StreamEvent.PermissionAsked)
    }
}
