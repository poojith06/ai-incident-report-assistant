package com.example.test_app.domain

import com.example.test_app.domain.config.SpeechRate
import com.example.test_app.domain.config.SpokenLanguages
import com.example.test_app.domain.config.UserSettings
import com.example.test_app.domain.config.UserSettingsStore
import com.example.test_app.domain.model.Clarification
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.platform.JsonFileReportRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultsAreFollowUpsOnHandsFreeOnReadAloudOnNormalRate() {
        val d = UserSettings()
        assertTrue(d.followUpsEnabled)
        assertTrue(d.handsFreeAnswers)
        assertTrue(d.readResultsAloud)
        assertEquals(SpeechRate.NORMAL, d.speechRate)
    }

    @Test fun speechRatesAreSlowNormalFast() {
        assertEquals(0.8f, SpeechRate.SLOW.value)
        assertEquals(1.0f, SpeechRate.NORMAL.value)
        assertEquals(1.25f, SpeechRate.FAST.value)
        assertEquals(listOf("Slow", "Normal", "Fast"), SpeechRate.entries.map { it.label })
    }

    @Test fun anUnknownStoredRateFallsBackToNormal() {
        assertEquals(SpeechRate.FAST, SpeechRate.fromName("FAST"))
        assertEquals(SpeechRate.NORMAL, SpeechRate.fromName(null))
        assertEquals(SpeechRate.NORMAL, SpeechRate.fromName("TURBO"))
    }

    @Test fun theStorePublishesAndPersistsOnlyRealChanges() {
        val saved = mutableListOf<UserSettings>()
        val store = UserSettingsStore { saved += it }
        store.update { it.copy(followUpsEnabled = false) }
        store.update { it.copy(followUpsEnabled = false) } // no change: not persisted again
        store.update { it.copy(speechRate = SpeechRate.SLOW) }
        assertEquals(2, saved.size)
        assertEquals(UserSettings(followUpsEnabled = false, speechRate = SpeechRate.SLOW), store.current)
        assertEquals(store.current, store.settings.value)
    }

    @Test fun spokenLanguageDefaultsToAutoAndForcesNothing() {
        val d = UserSettings()
        assertEquals("auto", d.spokenLanguage)
        assertEquals("auto", UserSettings.SPOKEN_AUTO)
        assertEquals(null, d.forcedLanguage)
        assertEquals(null, UserSettings(spokenLanguage = "Auto").forcedLanguage)
        assertEquals(null, UserSettings(spokenLanguage = "").forcedLanguage)
        assertEquals("Tamil", UserSettings(spokenLanguage = "Tamil").forcedLanguage)
    }

    @Test fun spokenLanguageRoundTripsThroughTheStore() {
        val saved = mutableListOf<UserSettings>()
        val store = UserSettingsStore { saved += it }
        store.update { it.copy(spokenLanguage = "Tamil") }
        store.update { it.copy(spokenLanguage = "Tamil") } // no change: not persisted again
        assertEquals(1, saved.size)
        assertEquals("Tamil", store.current.spokenLanguage)
        assertEquals("Tamil", store.settings.value.forcedLanguage)
        // what AppContainer stores (the name string) is what it reads back
        val restored = UserSettingsStore(UserSettings(spokenLanguage = saved.single().spokenLanguage))
        assertEquals("Tamil", restored.current.spokenLanguage)
        store.update { it.copy(spokenLanguage = UserSettings.SPOKEN_AUTO) }
        assertEquals(null, store.current.forcedLanguage)
    }

    @Test fun theLanguageListIsReadFromTheDataFile() {
        val file = listOf("src/main/assets/dialogue/languages.json", "app/src/main/assets/dialogue/languages.json").map { java.io.File(it) }.first { it.isFile }
        val names = SpokenLanguages.parse(file.readText(Charsets.UTF_8))
        assertEquals(
            listOf("English", "Hindi", "Tamil", "Telugu", "Kannada", "Malayalam", "Marathi", "Gujarati", "Punjabi", "Bengali", "Assamese", "Odia", "Urdu"),
            names,
        )
        assertTrue(names.none { it.equals("auto", ignoreCase = true) })
    }

    @Test fun theLanguageParserIsStrictAndTolerant() {
        assertEquals(listOf("Tamil", "Odia"), SpokenLanguages.parse("""{"languages": [" Tamil ", "", "tamil", "Odia"]}"""))
        for (bad in listOf("not json", "{}", """{"languages": []}""", """{"languages": [1]}""", """{"languages": ["Auto"]}""")) {
            try {
                SpokenLanguages.parse(bad)
                throw AssertionError("expected IllegalArgumentException for $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals("Auto-detect", SpokenLanguages.label("auto"))
        assertEquals("Auto-detect", SpokenLanguages.label(""))
        assertEquals("Tamil", SpokenLanguages.label("Tamil"))
    }

    @Test fun postIncidentAnalysisIsOnByDefaultAndPersistsWhenSwitchedOff() {
        assertTrue(UserSettings().postIncidentAnalysis)
        val saved = mutableListOf<UserSettings>()
        val store = UserSettingsStore { saved += it }
        store.update { it.copy(postIncidentAnalysis = false) }
        assertEquals(false, saved.single().postIncidentAnalysis)
        assertEquals(false, UserSettingsStore(UserSettings(postIncidentAnalysis = saved.single().postIncidentAnalysis)).current.postIncidentAnalysis)
    }

    @Test fun theStoreStartsFromTheGivenValues() {
        val store = UserSettingsStore(UserSettings(readResultsAloud = false))
        assertEquals(false, store.current.readResultsAloud)
    }

    // ---- "Delete all past reports" ----

    private fun report(id: String) = IncidentReport(
        reportId = id, status = "x", createdAt = "2026-10-11T10:00:0${id.last()}+05:30", input = ReportInput("voice", "t"),
        fields = emptyMap(), clarification = Clarification(),
    )

    @Test fun deleteAllRemovesEveryReportAndReportsTheCount() = runTest {
        val dir = tmp.newFolder("reports")
        val repo = JsonFileReportRepository(dir)
        repo.save(report("INC-1")); repo.save(report("INC-2")); repo.save(report("INC-3"))
        assertEquals(3, repo.list().size)
        assertEquals(3, repo.deleteAll())
        assertEquals(emptyList<IncidentReport>(), repo.list())
        assertEquals("nothing left to delete", 0, repo.deleteAll())
    }

    @Test fun deleteAllLeavesOtherFilesAndWorksWithoutTheFolder() = runTest {
        val dir = tmp.newFolder("reports")
        java.io.File(dir, "notes.txt").writeText("keep")
        val repo = JsonFileReportRepository(dir)
        repo.save(report("INC-1"))
        assertEquals(1, repo.deleteAll())
        assertTrue(java.io.File(dir, "notes.txt").exists())
        assertEquals(0, JsonFileReportRepository(java.io.File(tmp.root, "never-created")).deleteAll())
        repo.save(report("INC-2")) // still usable afterwards
        assertEquals(1, repo.list().size)
    }
}
