#!/usr/bin/env python3
"""Renders reference Gemma 4 prompts with the model's OWN jinja chat template and writes the golden file that the JVM test
`ChatFormatTest` compares `ChatFormat.GEMMA4` against (app/src/test/resources/chat_golden/gemma4.json).

The template is the `tokenizer.chat_template` of gemma-4-E2B_q4_0-it.gguf, read read-only from the board:

  adb -s <serial> exec-out "head -c 60000000 /data/local/tmp/models/gemma/gemma-4-E2B_q4_0-it.gguf" > build/models/gemma_head.bin
  python tools/gguf_meta.py build/models/gemma_head.bin --key tokenizer.chat_template > build/models/gemma_chat_template.jinja
  python tools/gemma_render_reference.py            # needs: pip install jinja2

`enable_thinking` is false (llama-server `--reasoning off`) and the leading `<bos>` is stripped, exactly like llama.cpp does when the vocab
adds BOS itself (common_chat_templates_apply). A media item is the literal marker `<__media__>` at the end of the user text (the
layout llama-server produces: text parts, then one marker per media item, no separator).
"""
import argparse
import json
import pathlib

import jinja2

M = "<__media__>"

CASES = [
    {
        "name": "transcribe",
        "messages": [{"role": "user", "content": "Transcribe this audio exactly. Output only the transcript, nothing else." + M}],
    },
    {
        "name": "system_fewshot",
        "messages": [
            {"role": "system", "content": "Extract incident report fields as JSON.\nUse null for anything not said."},
            {"role": "user", "content": "There was a water leak near Conference Room 2."},
            {"role": "assistant", "content": '{"incident_type": "Water Leak", "location": "Conference Room 2"}'},
            {"role": "user", "content": "Good morning, is this working?"},
            {"role": "assistant", "content": '{"incident_type": null, "location": null}'},
            {"role": "user", "content": "a pipe is leaking in the lab"},
        ],
    },
    {
        "name": "photo_and_audio",
        "messages": [
            {"role": "system", "content": "You write incident reports."},
            {"role": "user", "content": "Please take a look at the photo and listen to the audio and give an incident report." + M + M},
        ],
    },
    {
        "name": "rephrase",
        "messages": [
            {"role": "system", "content": "You rewrite ONE follow-up question.\nRules:\n- Ask exactly one question."},
            {"role": "user", "content": "Write the question now."},
        ],
    },
    {
        "name": "trim",
        "messages": [
            {"role": "system", "content": "  rules with trailing newline\n\n"},
            {"role": "user", "content": "\n  spaced text  \n"},
        ],
    },
    {"name": "user_only", "messages": [{"role": "user", "content": "hello"}]},
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--template", default="build/models/gemma_chat_template.jinja")
    ap.add_argument("--out", default="app/src/test/resources/chat_golden/gemma4.json")
    a = ap.parse_args()
    src = pathlib.Path(a.template).read_text(encoding="utf-8")

    def raise_exception(msg):
        raise jinja2.TemplateError(msg)

    env = jinja2.Environment(trim_blocks=True, lstrip_blocks=True)  # the settings of transformers' apply_chat_template
    env.globals["raise_exception"] = raise_exception
    tmpl = env.from_string(src)
    out = []
    for c in CASES:
        text = tmpl.render(messages=c["messages"], add_generation_prompt=True, enable_thinking=False, bos_token="<bos>")
        assert text.startswith("<bos>"), c["name"]
        out.append({"name": c["name"], "messages": c["messages"], "expected": text[len("<bos>"):]})
    p = pathlib.Path(a.out)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(out, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    for o in out:
        print("---", o["name"])
        print(o["expected"], end="<END>\n")


if __name__ == "__main__":
    main()
