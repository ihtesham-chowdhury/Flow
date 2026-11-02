package com.flowbangla.app.format

import org.junit.Assert.assertTrue
import org.junit.Test

class FormatValidatorTest {

    @Test
    fun testValidOutputPasses() {
        val raw = "কালকে মিটিং আছে দুপুর তিনটায়"
        val formatted = "কালকে দুপুর ৩টায় মিটিং আছে।"
        val result = FormatValidator.validate(raw, formatted)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun testEmptyOrPunctuationOnlyRejected() {
        val result1 = FormatValidator.validate("test", "")
        assertTrue(result1 is ValidationResult.Invalid)

        val result2 = FormatValidator.validate("test", "   ... ,,, !!!  ")
        assertTrue(result2 is ValidationResult.Invalid)
    }

    @Test
    fun testChattyPreambleRejected() {
        val raw = "Send the email to manager"
        val formatted = "Sure, here is your formatted text: Send the email to manager."
        val result = FormatValidator.validate(raw, formatted)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun testHallucinatedAnswerRejected() {
        val raw = "What is the capital of France?"
        val formatted = "The capital of France is Paris, which is a major European city located on the river Seine with a history spanning over two millennia and a population of several million residents who speak French."
        val result = FormatValidator.validate(raw, formatted)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun testProtectedTokenCheck() {
        val raw = "আমার ইমেইল user@example.com এ পাঠিয়ে দাও"
        val formattedWithoutEmail = "আমাকে পাঠিয়ে দাও।"
        val result = FormatValidator.validate(raw, formattedWithoutEmail, setOf("user@example.com"))
        assertTrue(result is ValidationResult.Invalid)
    }
}
