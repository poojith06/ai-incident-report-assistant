# Generalized Severity Estimation Framework & LLM Prompt Template

Derived from the empirical findings and taxonomies across 8 research studies spanning Occupational Safety (OSHA), Healthcare Patient Safety (WHO & Primary Care Harm Taxonomies), Traffic Crashes (KABCO Scale), Construction Collapses, and IT Incident Management.

---

## Part 1: Generalized Severity Estimation Methodology

Based on the synthesis of the reviewed literature, severity estimation cannot rely on simple keyword matching. Instead, it requires evaluating unstructured incident narratives across **5 Core Assessment Dimensions** to map the incident to a **5-Tier Universal Severity Scale**.

### 1. The 5 Core Assessment Dimensions

When analyzing an incident narrative, evaluate the following multi-dimensional attributes:

1. **Impact & Functional Impairment (Physical/Operational)**
   - *Physical*: Affected body parts (vital organs/head/trunk vs. extremities) and nature of injury (fracture, open wound, amputation, burn).
   - *Operational*: Degree of system/business service disruption (minor slowdown vs. full outage).
2. **Required Level of Intervention**
   - *Minimal*: Self-care, first aid, extra observation, minor setting adjustments.
   - *Moderate*: Standard medical treatment (antibiotics, IV fluids), emergency department assessment, localized system reboot/escalation.
   - *Major*: Surgical intervention, intensive care (ICU/CCU/HDU), major IT incident response team deployment.
3. **Temporal Duration & Permanence**
   - *Transient*: Resolves in hours (e.g., transient distress, brief pain, minor delay).
   - *Short-to-Medium Term*: Resolves in days to weeks.
   - *Permanent*: Major long-term impairment, loss of function, irreversible damage.
4. **Mitigating & Near-Miss Factors**
   - Was the harm prevented or minimized by timely safety action/intervention?
5. **Secondary / Psychological Impact**
   - Long-term psychological distress, anxiety, or broader financial/operational loss.

---

### 2. The 5-Tier Universal Severity Scale

```
[Level 0: None / Near-Miss] 
       │
[Level 1: Low / Mild]
       │
[Level 2: Medium / Moderate]
       │
[Level 3: High / Severe]
       │
[Level 4: Critical / Fatal / Catastrophic]
```

| Severity Level                                     | Definition & Criteria                                                                                                                                                                                                              | Indicative Evidence / Keywords                                                                                                                         |
| :------------------------------------------------- | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Level 0: None / Near-Miss**                | Incident ran to completion with**no harm**, or harm was **prevented** by a timely mitigating action.                                                                                                                   | *"no harm incurred"*, *"caught before administration"*, *"near-miss"*, *"corrected error"*                                                     |
| **Level 1: Low / Mild**                      | Symptomatic with**mild, transient impact** (resolves in hours); requires **no or minimal intervention**; transient emotional/minor inconvenience.                                                                      | *"first aid"*, *"resolved in hours"*, *"minor pain"*, *"transient distress"*, *"no treatment needed"*                                        |
| **Level 2: Medium / Moderate**               | Medium-term impact (resolves in days); requires**formal medical intervention** (e.g., antibiotics, IV, minor procedure) or **short-term ED/hospital assessment**; localized service disruption.                        | *"ED visit"*, *"overnight observation"*, *"prescribed antibiotics"*, *"resolved in days"*, *"moderate pain"*, *"service degradation"*      |
| **Level 3: High / Severe**                   | Major**long-term or permanent impact**; requires **major surgical/medical intervention**, **prolonged hospitalization**, **admission to ICU/CCU**, **amputation**, or **major system outage**. | *"amputation"*, *"ICU/CCU admission"*, *"surgery required"*, *"permanent loss of function"*, *"prolonged hospital stay"*, *"major outage"* |
| **Level 4: Critical / Fatal / Catastrophic** | Resulted in**death/fatality**, shortened life expectancy, or **catastrophic multi-system collapse**.                                                                                                                   | *"fatal"*, *"death"*, *"succumbed to injuries"*, *"catastrophic collapse"*, *"mass casualties"*                                              |

---

### 3. Decision-Tree Logic for Resolving Ambiguities

1. **Irreversible Outcomes First**: If an incident explicitly mentions **fatality** or **amputation**, immediately classify as **Critical (Level 4)** or **Severe (Level 3)** respectively, regardless of other text.
2. **Hospitalization Discriminator**:
   - Short-term observation / ED evaluation without major surgery $\rightarrow$ **Level 2 (Moderate)**.
   - Prolonged stay, ICU, CCU, or major surgery $\rightarrow$ **Level 3 (Severe)**.
3. **Psychological & Emotional Harm**:
   - Transient distress (anger, short anxiety) $\rightarrow$ **Level 1 (Mild)**.
   - Enduring psychological condition (chronic depression, PTSD, psychosis requiring specialist care) $\rightarrow$ **Level 3 (Severe)**.
4. **Conservative Safety Default**: If the boundary between two levels is ambiguous, assign the **higher severity rating** to prevent underestimating safety risks.

---

## Part 2: Production LLM System Prompt for Severity Estimation

Below is a prompt designed for zero-shot or few-shot severity classification using any LLM (e.g., Gemini, GPT-4, Claude).

```markdown
SYSTEM PROMPT:
You are an expert Safety & Risk Assessment AI. Your task is to evaluate unstructured incident reports and determine their severity level using a standardized 5-tier rulebook.

### SEVERITY RULEBOOK

- LEVEL 0: NONE / NEAR-MISS
  * Criteria: No harm occurred, or potential harm was completely mitigated before impacting the subject/system.
  * Keywords/Triggers: Near-miss, caught in time, corrected before impact, no injury, no harm.

- LEVEL 1: LOW / MILD
  * Criteria: Mild, transient impact resolving in hours; requires no or minimal intervention (first aid, observation); short-lived emotional distress.
  * Keywords/Triggers: First aid, minor pain, transient anxiety, resolved within hours, no medication required.

- LEVEL 2: MEDIUM / MODERATE
  * Criteria: Medium-term impact resolving in days; requires non-major medical treatment (antibiotics, IV fluids), short-term ED/hospital assessment, or localized service degradation.
  * Keywords/Triggers: ED assessment, overnight stay, prescribed treatment, stitches, fracture without displacement, resolved in days.

- LEVEL 3: HIGH / SEVERE
  * Criteria: Major long-term or permanent impact; requires major surgery, prolonged hospitalization, ICU/CCU admission, amputation, or major infrastructure outage.
  * Keywords/Triggers: Amputation, ICU/CCU, major surgery, permanent loss of function, prolonged stay, structural collapse.

- LEVEL 4: CRITICAL / FATAL
  * Criteria: Fatality, shortened life expectancy, or catastrophic multi-system loss.
  * Keywords/Triggers: Death, fatal, deceased, mass casualty, total system collapse.

### INSTRUCTIONS

1. Analyze the input incident narrative carefully across these dimensions:
   a. Extent of physical/operational impairment.
   b. Level of medical/technical intervention required.
   c. Temporal duration (transient vs. days vs. permanent).
   d. Presence of irreversible factors (death, amputation).

2. Output your reasoning and classification strictly in JSON format as follows:

```json
{
  "reasoning": "Step-by-step evaluation of the narrative against the core dimensions.",
  "extracted_evidence": ["list of specific phrases from text supporting the rating"],
  "severity_level": "LEVEL_NAME (Level X: Name)",
  "severity_score": INTEGER (0 to 4),
  "confidence_score": FLOAT (0.0 to 1.0)
}
```

### INPUT INCIDENT NARRATIVE:

[INSERT INCIDENT NARRATIVE HERE]

```
```
