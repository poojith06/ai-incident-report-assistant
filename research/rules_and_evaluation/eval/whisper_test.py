# whisper_test.py
# 1) Generates incident voice clips with neural TTS voices (Telugu, Hindi, Indian English)
# 2) Runs Whisper-Small on each clip in transcribe and translate mode
# 3) Saves everything to whisper_results.txt
#
# Setup (once):   pip install edge-tts
# Run:            python whisper_test.py
#
# Note: TTS audio is clean studio-quality speech, so it is easier than real speech.
# Use it to compare transcribe vs translate; confirm later with a few real recordings.

import asyncio
import os

import edge_tts
import whisper

CLIPS = [
    # (file name, edge-tts voice, whisper language code, text, meaning)
    ("te_fire",   "te-IN-ShrutiNeural", "te", "ల్యాబ్‌లో నిప్పు అంటుకుంది, త్వరగా రండి",
     "Fire in the lab, come quickly"),
    ("te_injury", "te-IN-MohanNeural",  "te", "క్యాంటీన్ దగ్గర ఒక అబ్బాయి పడిపోయాడు, కాలికి దెబ్బ తగిలింది, పది నిమిషాల క్రితం",
     "A boy fell near the canteen, hurt his leg, ten minutes ago"),
    ("te_leak",   "te-IN-ShrutiNeural", "te", "వాష్‌రూమ్‌లో నీళ్ళు లీక్ అవుతున్నాయి, పొద్దున్నుంచి",
     "Water leaking in the washroom since morning"),
    ("te_gas",    "te-IN-MohanNeural",  "te", "కిచెన్‌లో గ్యాస్ వాసన వస్తోంది",
     "Gas smell in the kitchen"),
    ("hi_injury", "hi-IN-MadhurNeural", "hi", "कैंटीन के पास एक आदमी गिर गया, उसके पैर में चोट लगी है, दस मिनट पहले",
     "A man fell near the canteen, his leg is hurt, ten minutes ago"),
    ("hi_fire",   "hi-IN-SwaraNeural",  "hi", "लैब में आग लग गई है, जल्दी आओ",
     "Fire in the lab, come quickly"),
    ("hi_equip",  "hi-IN-MadhurNeural", "hi", "सर्वर रूम में एसी खराब हो गया है",
     "The AC in the server room has broken down"),
    ("en_leak",   "en-IN-NeerjaNeural", "en", "There's a water leak near the server room, it started around ten this morning.",
     "(English)"),
]

OUT_DIR = "audio_tests"


async def make_audio():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, voice, _, text, _ in CLIPS:
        path = os.path.join(OUT_DIR, name + ".mp3")
        if os.path.exists(path):
            continue
        print(f"Generating {path} ...")
        await edge_tts.Communicate(text, voice).save(path)


def run_whisper():
    print("\nLoading Whisper-Small (first run downloads ~460 MB) ...")
    model = whisper.load_model("small")
    lines = []
    for name, voice, lang, text, meaning in CLIPS:
        path = os.path.join(OUT_DIR, name + ".mp3")
        lines.append(f"=== {name}  [{lang}]  meaning: {meaning}")
        tasks = ["transcribe"] if lang == "en" else ["transcribe", "translate"]
        for task in tasks:
            r = whisper.transcribe(model, path, task=task, language=lang, fp16=False)
            out = r["text"].strip()
            lines.append(f"  {task:10s}: {out}")
            print(f"{name:10s} {task:10s}: {out}")
        lines.append("")
    with open("whisper_results.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\nSaved whisper_results.txt")


if __name__ == "__main__":
    asyncio.run(make_audio())
    run_whisper()
