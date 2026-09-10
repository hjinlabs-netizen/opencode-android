package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.domain.model.StreamEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
