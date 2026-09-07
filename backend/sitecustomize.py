import os

print(
    f"JARVIS_BOOT_OPENAI_CONFIGURED={bool(os.getenv('OPENAI_API_KEY', '').strip())}",
    flush=True,
)
