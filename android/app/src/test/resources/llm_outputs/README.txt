Hand-written raw model outputs (the text the engine returns AFTER the forced assistant prefix).
They imitate Qwen3-4B quirks (think blocks, repeated prefix, chat tokens, truncation, fences).
They are hand-written until real device outputs are saved; replace/add real captures here and keep the test names.
extract_*: continuation of the extraction prefix `{"incident_type":`; update_*: continuation of the update prefix `{`;
rephrase_*: reply to the rephrase prompt (empty think block as assistant prefix).
gemma_*: HAND-WRITTEN imitations of Gemma 4 E2B output (never captured on the device yet): the continuation after the forced prefix
(gemma_extract_*: `{"incident_type":`; gemma_multimodal_*: `{"transcript":`; gemma_photo: `{"description":`), `<turn|>` end of turn,
an empty `<|channel>thought\n<channel|>` block, a transcript with a label, a truncated one-call reply. Replace with real captures after
the first device run (doc/TESTING.md "Gemma on the device").
