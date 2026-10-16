package com.example.test_app.knowledgegraph

import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.knowledgegraph.engine.EngineConfig
import com.example.test_app.knowledgegraph.engine.KnowledgeGraphEngine
import com.example.test_app.knowledgegraph.history.HistoryPatternDetector
import com.example.test_app.knowledgegraph.llmgrounding.ApprovedFact
import com.example.test_app.knowledgegraph.llmgrounding.GroundedRecommendationRenderer
import com.example.test_app.knowledgegraph.llmgrounding.TemplateRenderer
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.RecommendationItem
import com.example.test_app.knowledgegraph.model.RecommendationSet
import com.example.test_app.knowledgegraph.model.ValidatedIncident
import com.example.test_app.knowledgegraph.repository.AnalysisOutbox
import com.example.test_app.knowledgegraph.repository.AuditStore
import com.example.test_app.knowledgegraph.repository.GraphIndex
import com.example.test_app.knowledgegraph.repository.GraphRepository
import java.time.Instant

/** What the app shows after a submit. */
sealed interface AnalysisResult {
    class Done(val incident: ValidatedIncident, val set: RecommendationSet, val export: IncidentAnalysisExport) : AnalysisResult
    class Failed(val message: String) : AnalysisResult
}

/**
 * Orchestrates one post-incident analysis (ADR-055, ADR-056): report -> `ValidatedIncident` -> history patterns -> engine ->
 * grounded rendering (template by default; the LLM path only when a renderer is supplied) -> audit log -> export queued in the outbox.
 * Everything is pure Kotlin and offline. It NEVER throws for analysis problems (it returns [AnalysisResult.Failed]) so the report
 * submission it follows cannot be affected; only coroutine cancellation propagates.
 */
class KnowledgeGraphService(
    private val repository: GraphRepository,
    private val auditStore: AuditStore,
    private val outbox: AnalysisOutbox,
    private val renderer: () -> GroundedRecommendationRenderer = { TemplateRenderer() },
    /** The engine settings, read on every analysis (the admin dashboard can change them at any time). */
    private val config: () -> EngineConfig = { EngineConfig() },
    private val clock: () -> Instant = { Instant.now() },
) {
    private var cached: GraphIndex? = null

    /** The index of the active pack; rebuilt when an import, edit or rollback changed the active pack. */
    @Synchronized private fun index(): GraphIndex {
        val pack = repository.activePack()
        return cached?.takeIf { it.pack === pack } ?: GraphIndex(pack).also { cached = it }
    }

    private val detector = HistoryPatternDetector { index() }

    /**
     * Analyses a SUBMITTED [report]. [saved] are the stored reports (the history; the report itself may be among them).
     * [finalSeverity] is the severity the app decided (defaults to the report's field).
     */
    suspend fun analyze(report: IncidentReport, saved: List<IncidentReport>, finalSeverity: String? = null): AnalysisResult {
        return try {
            val rules = index().pack.normalization
            val incident = KnowledgeGraphInput.fromReport(report, rules, finalSeverity)
            val current = KnowledgeGraphInput.historic(report, rules)
            val citations = detector.detect(current, saved.filter { it.status == IncidentReport.STATUS_SUBMITTED }.map { KnowledgeGraphInput.historic(it, rules) })
            val result = KnowledgeGraphEngine({ index() }, null, config(), clock).analyze(incident, Audience.REPORTER, citations)
            val rendered = render(result.set)
            val shown = result.log.shown.map { e -> rendered.allRecommendations.firstOrNull { it.id == e.id }?.let { e.copy(renderMode = it.renderMode.name) } ?: e }
            val log = result.log.copy(shown = shown)
            auditStore.save(log)
            val export = AnalysisExportBuilder.build(incident, rendered, rules, clock().toString())
            outbox.enqueue(export)
            AnalysisResult.Done(incident, rendered, export)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AnalysisResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    private suspend fun render(set: RecommendationSet): RecommendationSet {
        val items = set.allRecommendations
        if (items.isEmpty()) return set
        val facts = items.map { ApprovedFact(it.id, it.canonicalText, it.ownerTeamName) }
        val rendered = renderer().render(facts).associateBy { it.recommendationId }
        fun patch(list: List<RecommendationItem>) = list.map { r -> rendered[r.id]?.let { r.copy(renderedText = it.text, renderMode = it.mode) } ?: r }
        return set.copy(
            immediateActions = patch(set.immediateActions), operationalSteps = patch(set.operationalSteps),
            recoverySteps = patch(set.recoverySteps), preventiveActions = patch(set.preventiveActions),
        )
    }
}
