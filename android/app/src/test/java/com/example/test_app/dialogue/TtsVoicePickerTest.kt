package com.example.test_app.dialogue

import com.example.test_app.platform.TtsVoicePicker
import com.example.test_app.platform.TtsVoicePicker.Candidate
import com.example.test_app.platform.TtsVoicePicker.Pick
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsVoicePickerTest {
    private fun v(name: String, country: String, net: Boolean = false, missing: Boolean = false, lang: String = "en") =
        Candidate(name, lang, country, net, missing)

    @Test fun prefersAnInstalledOfflineVoiceOfTheDeviceCountry() {
        val voices = listOf(v("us-net", "US", net = true), v("us", "US"), v("in", "IN"), v("gb", "GB"))
        assertEquals(Pick.Voice("in"), TtsVoicePicker.pick(voices, "IN"))
        assertEquals(Pick.Voice("us"), TtsVoicePicker.pick(voices, "FR"))
    }

    @Test fun skipsNetworkAndNotInstalledVoices() {
        assertEquals(Pick.Voice("gb"), TtsVoicePicker.pick(listOf(v("us-net", "US", net = true), v("us-missing", "US", missing = true), v("gb", "GB")), "US"))
    }

    @Test fun onlyNetworkVoicesMeansUnavailable() {
        assertEquals(Pick.OnlyNetwork, TtsVoicePicker.pick(listOf(v("a", "US", net = true), v("b", "GB", missing = true)), "US"))
    }

    @Test fun noEnglishVoiceListFallsBackToTheEngineDefault() {
        assertEquals(Pick.NoVoiceList, TtsVoicePicker.pick(emptyList(), "IN"))
        assertEquals(Pick.NoVoiceList, TtsVoicePicker.pick(listOf(v("hi", "IN", lang = "hi")), "IN"))
    }

    @Test fun anyOtherEnglishOfflineVoiceIsAcceptable() {
        assertEquals(Pick.Voice("au"), TtsVoicePicker.pick(listOf(v("au", "AU")), "IN"))
    }
}
