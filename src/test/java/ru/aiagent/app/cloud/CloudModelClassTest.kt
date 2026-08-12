package ru.aiagent.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.aiagent.core.agent.ModelClass

/**
 * L8 для облака: слабая/дешёвая модель НЕ должна получать полный опасный набор инструментов.
 * Ревью нашло, что порядок проверок был обратным — токены флагманов являются ПРЕФИКСАМИ своих
 * мелких вариантов, поэтому mini/nano/flash классифицировались как STRONG и L8 не работал вовсе.
 */
class CloudModelClassTest {

    @Test fun cheapVariantsOfFlagshipsAreNotStrong() {
        // Каждый из них содержит токен флагмана как подстроку — ловушка исходного бага.
        assertEquals("gpt-4o-mini ⊃ gpt-4o", ModelClass.WEAK, cloudModelClass("openai/gpt-4o-mini"))
        assertEquals("gpt-5-mini ⊃ gpt-5", ModelClass.WEAK, cloudModelClass("openai/gpt-5-mini"))
        assertEquals("gpt-5-nano ⊃ gpt-5", ModelClass.WEAK, cloudModelClass("openai/gpt-5-nano"))
        assertEquals("distill ⊃ deepseek-r1", ModelClass.WEAK, cloudModelClass("deepseek/deepseek-r1-distill-llama-8b"))
        // Дефолтный исполнитель оркестратора — именно он выдавал полный набор.
        assertEquals("flash ⊃ deepseek-v", ModelClass.WEAK, cloudModelClass("deepseek/deepseek-v4-flash"))
        assertEquals("gemini flash", ModelClass.WEAK, cloudModelClass("google/gemini-2.5-flash"))
    }

    @Test fun realFlagshipsStayStrong() {
        assertEquals(ModelClass.STRONG, cloudModelClass("anthropic/claude-sonnet-5"))
        assertEquals(ModelClass.STRONG, cloudModelClass("openai/gpt-5"))
        assertEquals(ModelClass.STRONG, cloudModelClass("deepseek/deepseek-chat"))
        // "mini" сидит внутри "ge-mini-" — подстрочная проверка топила все gemini в WEAK.
        assertEquals(ModelClass.STRONG, cloudModelClass("google/gemini-2.5-pro"))
        assertEquals(ModelClass.STRONG, cloudModelClass("x-ai/grok-4"))
    }

    @Test fun sizeFromIdAndUnknownFallback() {
        assertEquals("70B+ → сильная", ModelClass.STRONG, cloudModelClass("meta/llama-3.3-70b-instruct"))
        assertEquals("32B → средняя", ModelClass.STANDARD, cloudModelClass("qwen/qwen2.5-32b"))
        assertEquals("13B → слабая (порог <15B)", ModelClass.WEAK, cloudModelClass("some/model-13b"))
        // Двузначный размер не должен ловиться размерным токеном одиночной цифры ("-70b" ≠ "-7b").
        assertEquals("70B не WEAK", ModelClass.STRONG, cloudModelClass("some/model-70b"))
        // Неизвестная модель — консервативно STANDARD (без DANGEROUS), а не STRONG.
        assertEquals(ModelClass.STANDARD, cloudModelClass("vendor/unknown-model"))
    }
}
