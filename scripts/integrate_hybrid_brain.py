from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
activity = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "jarvis" / "watchbridge" / "MainActivity.kt"


def patch() -> bool:
    text = activity.read_text()
    original = text

    import_line = "import com.jarvis.watchbridge.ai.HybridBrain\n"
    if import_line not in text:
        anchor = "import com.jarvis.watchbridge.ai.ChatRepository\n"
        if anchor not in text:
            raise RuntimeError("ChatRepository import anchor not found")
        text = text.replace(anchor, anchor + import_line, 1)

    panel_import = "import com.jarvis.watchbridge.ui.KnowledgeControlPanel\n"
    if panel_import not in text:
        anchor = "import com.jarvis.watchbridge.ui.ExpertTeamPanel\n"
        if anchor not in text:
            raise RuntimeError("ExpertTeamPanel import anchor not found")
        text = text.replace(anchor, anchor + panel_import, 1)

    text = text.replace("    private val chat = ChatRepository()\n", "    private lateinit var brain: HybridBrain\n", 1)

    init_anchor = "        moodEngine = MoodEngine(this)\n"
    if "        brain = HybridBrain(this)\n" not in text:
        if init_anchor not in text:
            raise RuntimeError("MoodEngine initialization anchor not found")
        text = text.replace(init_anchor, init_anchor + "        brain = HybridBrain(this)\n", 1)

    if "chat.send(msg, adaptiveContext)" in text:
        text = text.replace("chat.send(msg, adaptiveContext)", "brain.answer(msg, adaptiveContext).text", 1)
    elif "brain.answer(msg, adaptiveContext).text" not in text:
        raise RuntimeError("Live chat call anchor not found")

    if "KnowledgeControlPanel()" not in text:
        anchor = "                        item {\n                            ExpertTeamPanel()\n                        }\n"
        if anchor not in text:
            raise RuntimeError("ExpertTeamPanel item anchor not found")
        text = text.replace(anchor, anchor + "\n                        item {\n                            KnowledgeControlPanel()\n                        }\n", 1)

    destroy_anchor = "    override fun onDestroy() {\n"
    if "        brain.shutdown()\n" not in text:
        if destroy_anchor not in text:
            raise RuntimeError("onDestroy anchor not found")
        text = text.replace(destroy_anchor, destroy_anchor + "        brain.shutdown()\n", 1)

    if text != original:
        activity.write_text(text)
        return True
    return False


if __name__ == "__main__":
    print(f"hybrid_changed={patch()}")
