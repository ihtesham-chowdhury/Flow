package com.flowbangla.app.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatterResponseParserTest {

    @Test
    fun testValidJsonParsing() {
        val json = """
            {
              "document_type": "task_list",
              "detected_language": "BANGLA",
              "formatted_text": "• কাজ ১\n• কাজ ২"
            }
        """.trimIndent()

        val parsed = FormatterResponseParser.parse(json)
        assertTrue(parsed.isParsedFromJson)
        assertEquals(DocumentType.TASK_LIST, parsed.documentType)
        assertEquals("• কাজ ১\n• কাজ ২", parsed.formattedText)
    }

    @Test
    fun testMarkdownCodeFencesStripped() {
        val markdownJson = """
            ```json
            {
              "document_type": "email",
              "detected_language": "BANGLA_ENGLISH_MIXED",
              "formatted_text": "Hi John,\nHope you are doing well."
            }
            ```
        """.trimIndent()

        val parsed = FormatterResponseParser.parse(markdownJson)
        assertTrue(parsed.isParsedFromJson)
        assertEquals(DocumentType.EMAIL, parsed.documentType)
        assertEquals("Hi John,\nHope you are doing well.", parsed.formattedText)
    }

    @Test
    fun testRawTextFallback() {
        val raw = "Plain unformatted string from model"
        val parsed = FormatterResponseParser.parse(raw)
        assertEquals("Plain unformatted string from model", parsed.formattedText)
    }
}
