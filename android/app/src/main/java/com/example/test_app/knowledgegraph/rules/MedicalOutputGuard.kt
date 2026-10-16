package com.example.test_app.knowledgegraph.rules

/**
 * Spec section 3 and 13: no diagnosis, no medicine or dosage, no treatment plan or home remedy. Scans any text that could reach a user
 * (canonical graph text, LLM output, imports). Returns the names of the violated rules (empty = clean). Deliberately blunt: a false
 * positive only suppresses a suggestion, a false negative could hurt someone.
 */
object MedicalOutputGuard {
    const val FOOTER = "This is immediate safety guidance, not medical advice. Contact campus medical services or qualified emergency personnel."

    private val RULES: List<Pair<String, Regex>> = listOf(
        "medication" to Regex("\\b(medicine|medicines|medication|medications|medicate|tablet|tablets|pill|pills|capsule|syrup|ointment|cream|painkiller|painkillers|antibiotic|antibiotics|paracetamol|ibuprofen|aspirin|antacid|drug|drugs|injection|vaccine)\\b", RegexOption.IGNORE_CASE),
        "dosage" to Regex("\\b(dose|doses|dosage|mg|ml|milligrams?|millilitres?|milliliters?)\\b", RegexOption.IGNORE_CASE),
        "treatment" to Regex("\\b(treat|treats|treated|treating|treatment|treatments|therapy|remedy|remedies|cure|cures|heal|prescribe|prescribed|prescription)\\b", RegexOption.IGNORE_CASE),
        "diagnosis" to Regex("\\b(diagnos\\w*|you (may |might |probably |likely )?have|it is (a|an) (fracture|sprain|strain|concussion|burn|break)|(is|are) (broken|fractured|sprained|concussed))\\b", RegexOption.IGNORE_CASE),
        "injury-name" to Regex("\\b(fracture|fractured|sprain|sprained|strain|concussion|dislocat\\w*|torn ligament|internal bleeding|heart attack|stroke|infection|fever|poisoning)\\b", RegexOption.IGNORE_CASE),
        "home-remedy" to Regex("\\b(home remedy|ice pack|apply ice|apply pressure|bandage|splint|tourniquet|massage|rub the|drink water|take rest)\\b", RegexOption.IGNORE_CASE),
    )

    /** Names of the rules the [text] violates (a stable order); empty when it is clean. */
    fun violations(text: String): List<String> = RULES.filter { (_, re) -> re.containsMatchIn(text) }.map { it.first }

    fun isClean(text: String): Boolean = violations(text).isEmpty()
}
