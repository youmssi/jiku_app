package com.jiku.messaging.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SmsTextTest {
    @Test
    fun `accents missing from GSM-7 lose their accent and the ones it has are kept`() {
        assertThat(SmsText.toGsm7("Jikū : connaître votre fête, déjà à Conakry"))
            .isEqualTo("Jiku : connaitre votre fete, déjà à Conakry")
    }

    @Test
    fun `typographic punctuation becomes its plain form`() {
        assertThat(SmsText.toGsm7("L’accès « VIP » — 20 h…")).isEqualTo("L'accès \"VIP\" - 20 h...")
    }

    @Test
    fun `characters without a GSM-7 equivalent are dropped`() {
        assertThat(SmsText.toGsm7("Bienvenue 🎉 à la fête 中")).isEqualTo("Bienvenue à la fete")
    }

    @Test
    fun `extension characters are kept`() {
        assertThat(SmsText.toGsm7("Prix : 10 € [VIP]")).isEqualTo("Prix : 10 € [VIP]")
    }

    @Test
    fun `segments follow the GSM-7 limits`() {
        assertThat(SmsText.segments("")).isEqualTo(0)
        assertThat(SmsText.segments("a".repeat(160))).isEqualTo(1)
        assertThat(SmsText.segments("a".repeat(161))).isEqualTo(2)
        assertThat(SmsText.segments("a".repeat(306))).isEqualTo(2)
        assertThat(SmsText.segments("a".repeat(307))).isEqualTo(3)
        assertThat(SmsText.segments("€".repeat(80))).isEqualTo(1)
        assertThat(SmsText.segments("€".repeat(81))).isEqualTo(2)
    }

    @Test
    fun `the phone code message fits in one SMS`() {
        val body =
            SmsText.toGsm7(
                "Jikū : votre code de vérification est 123456. Il expire dans 10 minutes. Ne le partagez avec personne.",
            )
        assertThat(body).startsWith("Jiku : ")
        assertThat(SmsText.segments(body)).isEqualTo(1)
    }
}
