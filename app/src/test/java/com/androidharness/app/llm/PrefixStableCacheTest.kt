package com.androidharness.app.llm

import com.androidharness.app.agent.AgentEngine
import com.androidharness.app.core.*
import org.junit.Assert.*
import org.junit.Test

/**
 * DeepSeek bills input that repeats the start of a recent request at its cache-hit price, so on
 * DeepSeek endpoints each request must start exactly like the previous one.
 */
class PrefixStableCacheTest {
    @Test fun `only DeepSeek endpoints keep a stable prefix`() {
        assertTrue(PromptCachePolicy.prefixStable("https://api.deepseek.com"))
        assertTrue(PromptCachePolicy.prefixStable("https://api.deepseek.com/v1"))
        assertFalse(PromptCachePolicy.prefixStable("https://api.openai.com/v1"))
        assertFalse(PromptCachePolicy.prefixStable("https://notdeepseek.com"))
        assertFalse(PromptCachePolicy.prefixStable("not a url"))
    }

    @Test fun `app notes stay in place and join the neighbouring user message`() {
        val history = listOf(
            ChatMessage(role = Role.USER, text = "first"),
            ChatMessage(role = Role.ASSISTANT, text = "reply"),
            ChatMessage(role = Role.SYSTEM, text = "summary of earlier work"),
            ChatMessage(role = Role.USER, text = "second"),
        )
        val out = CompatHistory.notesInPlace(history)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), out.map { it.role })
        assertTrue(out[2].text.startsWith("[Note recorded by the app"))
        assertTrue(out[2].text.contains("summary of earlier work") && out[2].text.endsWith("second"))
    }

    @Test fun `a later note never changes how earlier messages are sent`() {
        val before = listOf(ChatMessage(role = Role.USER, text = "a"), ChatMessage(role = Role.ASSISTANT, text = "b"))
        val after = before + ChatMessage(role = Role.SYSTEM, text = "late note") + ChatMessage(role = Role.USER, text = "c")
        val sentBefore = CompatHistory.notesInPlace(before)
        val sentAfter = CompatHistory.notesInPlace(after)
        assertEquals(sentBefore, sentAfter.take(sentBefore.size))
    }

    @Test fun `the request tail joins the final user message without touching the saved history`() {
        val history = listOf(ChatMessage(role = Role.USER, text = "do it"))
        val sent = AgentEngine.withRequestTail(history, "todo: one")
        assertEquals(1, sent.size)
        assertTrue(sent[0].text.startsWith("do it\n\n<runtime_context>"))
        assertTrue(sent[0].text.contains("todo: one"))
        assertEquals("do it", history[0].text)
    }

    @Test fun `after tool results the request tail is its own user message`() {
        val history = listOf(
            ChatMessage(role = Role.USER, text = "go"),
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(ToolCallData("1", "shell", "{}"))),
            ChatMessage(role = Role.TOOL, toolCallId = "1", text = "ok"),
        )
        val sent = AgentEngine.withRequestTail(history, "status")
        assertEquals(history, sent.dropLast(1))
        assertEquals(Role.USER, sent.last().role)
        assertSame(history, AgentEngine.withRequestTail(history, "  "))
    }

    @Test fun `consecutive requests share every message before the tail`() {
        val turn = mutableListOf(ChatMessage(role = Role.USER, text = "go"),
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(ToolCallData("1", "shell", "{}"))),
            ChatMessage(role = Role.TOOL, toolCallId = "1", text = "first result"))
        val first = AgentEngine.withRequestTail(turn, "round 1 status")
        turn += ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(ToolCallData("2", "shell", "{}")))
        turn += ChatMessage(role = Role.TOOL, toolCallId = "2", text = "second result")
        val second = AgentEngine.withRequestTail(turn, "round 2 status")
        assertEquals(first.dropLast(1), second.take(first.size - 1))
    }
}
