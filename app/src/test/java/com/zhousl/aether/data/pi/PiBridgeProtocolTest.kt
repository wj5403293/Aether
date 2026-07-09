package com.zhousl.aether.data.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiBridgeProtocolTest {
    @Test
    fun parserHandlesFragmentedJsonlAndCrlf() {
        val frames = mutableListOf<PiBridgeFrame>()
        val parser = PiJsonlParser(onFrame = frames::add)

        parser.accept("""{"type":"response","id":"a","ok":true,"payload":{"x":""")
        parser.accept("1}}\r\n")
        parser.accept("""{"type":"event","id":"a","event":"assistant_text_delta","payload":{"delta":"hi"}}""" + "\n")

        assertEquals(2, frames.size)
        assertEquals("response", frames[0].type)
        assertEquals("a", frames[0].id)
        assertTrue(frames[0].ok)
        assertEquals(1, frames[0].payload.optInt("x"))
        assertEquals("event", frames[1].type)
        assertEquals("assistant_text_delta", frames[1].event)
        assertEquals("hi", frames[1].payload.optString("delta"))
    }

    @Test
    fun parserReportsInvalidJsonAndContinues() {
        val frames = mutableListOf<PiBridgeFrame>()
        val invalidLines = mutableListOf<String>()
        val parser = PiJsonlParser(
            onFrame = frames::add,
            onInvalidLine = { line, _ -> invalidLines += line },
        )

        parser.accept("not-json\n")
        parser.accept("""{"type":"response","id":"b","ok":true,"payload":{}}""" + "\n")

        assertEquals(listOf("not-json"), invalidLines)
        assertEquals(1, frames.size)
        assertEquals("b", frames[0].id)
    }

    @Test
    fun requestSerializesSnakeCasePayload() {
        val line = PiBridgeRequest(
            id = "req",
            type = "complete_once",
            payload = fauxPiModelConfig(response = "done").toJson(),
        ).toJsonLine()

        assertTrue(line.contains("\"type\":\"complete_once\""))
        assertTrue(line.contains("\"pi_provider_id\":\"faux\""))
        assertTrue(line.contains("\"faux_response\":\"done\""))
    }
}
