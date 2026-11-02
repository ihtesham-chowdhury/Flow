package com.flowbangla.app.format

import org.json.JSONObject

data class FormattedEnvelope(
    val formattedText: String,
    val documentType: DocumentType = DocumentType.GENERAL_DICTATION,
    val detectedLanguage: String = "BANGLA_ENGLISH_MIXED",
    val isParsedFromJson: Boolean = true
)

object FormatterResponseParser {

    fun parse(rawResponse: String?): FormattedEnvelope {
        if (rawResponse.isNullOrBlank()) {
            return FormattedEnvelope(formattedText = "", isParsedFromJson = false)
        }

        val cleaned = cleanResponseText(rawResponse)

        return try {
            // Find JSON boundary if surrounded by extraneous text
            val jsonStart = cleaned.indexOf('{')
            val jsonEnd = cleaned.lastIndexOf('}')
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val jsonSubstring = cleaned.substring(jsonStart, jsonEnd + 1)
                val json = JSONObject(jsonSubstring)
                val text = json.optString("formatted_text", "").trim()
                val docTypeStr = json.optString("document_type", DocumentType.GENERAL_DICTATION.id)
                val lang = json.optString("detected_language", "BANGLA_ENGLISH_MIXED")

                if (text.isNotBlank()) {
                    FormattedEnvelope(
                        formattedText = text,
                        documentType = DocumentType.fromId(docTypeStr),
                        detectedLanguage = lang,
                        isParsedFromJson = true
                    )
                } else {
                    // JSON parsed but formatted_text was empty, fallback to clean text
                    FormattedEnvelope(
                        formattedText = cleaned,
                        isParsedFromJson = false
                    )
                }
            } else {
                FormattedEnvelope(
                    formattedText = cleaned,
                    isParsedFromJson = false
                )
            }
        } catch (_: Exception) {
            FormattedEnvelope(
                formattedText = cleaned,
                isParsedFromJson = false
            )
        }
    }

    private fun cleanResponseText(input: String): String {
        return input.trim()
            .removePrefix("```json")
            .removePrefix("```text")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
