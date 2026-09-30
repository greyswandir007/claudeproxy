package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Оценка числа токенов: стандартное правило, калиброванный коэффициент, картинки. */
class OpenAiTokenCountEstimatorTest {

    private val objectMapper = ObjectMapper()
    private val estimator = OpenAiTokenCountEstimator()

    @Test
    fun `стандартная оценка - 4 символа на токен`() {
        // system 16 символов + content 8 = 24 → 24/4 = 6
        val root = objectMapper.readTree(
            """{"system":"0123456789abcdef","messages":[{"role":"user","content":"12345678"}]}""",
        )
        assertEquals(6L, estimator.estimate(root))
    }

    @Test
    fun `калиброванный коэффициент заменяет стандартный`() {
        val root = objectMapper.readTree(
            """{"system":"0123456789abcdef","messages":[{"role":"user","content":"12345678"}]}""",
        )
        assertEquals(2L, estimator.estimate(root, 12.0))
        assertEquals(12L, estimator.estimate(root, 2.0))
    }

    @Test
    fun `картинки добавляют фиксированные токены`() {
        // текстовый блок: «abcd» + значения полей блока; картинка не считается текстом
        val root = objectMapper.readTree(
            """{"messages":[{"role":"user","content":[
               {"type":"text","text":"abcd"},
               {"type":"image","source":{"type":"base64","media_type":"image/png","data":"xx"}}]}]}""",
        )
        assertEquals(1600L + 3L, estimator.estimate(root))
    }

    @Test
    fun `текстовые символы - та же база, что у оценки`() {
        val root = objectMapper.readTree(
            """{"system":"0123456789abcdef","messages":[{"role":"user","content":"12345678"}]}""",
        )
        assertEquals(24L, estimator.textCharacterCount(root))
    }

    @Test
    fun `текстовые токены вычитают фиксированную оценку картинок`() {
        val root = objectMapper.readTree(
            """{"messages":[{"role":"user","content":[
               {"type":"text","text":"abcd"},
               {"type":"image","source":{"type":"base64","media_type":"image/png","data":"xx"}},
               {"type":"image","source":{"type":"base64","media_type":"image/png","data":"yy"}}]}]}""",
        )
        assertEquals(1800L, estimator.textTokenCount(root, 5000L))
        assertEquals(5000L, estimator.textTokenCount(objectMapper.readTree("""{"messages":[]}"""), 5000L))
    }
}
