import os
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from openai import OpenAI

app = FastAPI(title="JARVIS Watch Bridge API", version="0.1.0")

class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    health_context: str | None = Field(default=None, max_length=8000)

class ChatResponse(BaseModel):
    reply: str

@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}

@app.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest) -> ChatResponse:
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")

    client = OpenAI(api_key=api_key)
    context = req.health_context or "No health context supplied."
    instructions = (
        "You are JARVIS Watch Bridge, a concise personal AI assistant. "
        "When health metrics are provided, treat them as wellness data only, not diagnosis. "
        "For potentially urgent symptoms or dangerous readings, advise appropriate professional or emergency care. "
        "Prefer short replies suitable for a smartwatch notification when possible."
    )
    response = client.responses.create(
        model="gpt-5.6-terra",
        instructions=instructions,
        input=f"Health context:\n{context}\n\nUser:\n{req.message}",
    )
    return ChatResponse(reply=response.output_text.strip())
