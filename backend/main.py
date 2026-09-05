import os
from typing import Any

import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from openai import OpenAI

app = FastAPI(title="JARVIS Watch Bridge API", version="0.3.0")
VAPI_BASE = "https://api.vapi.ai"


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    health_context: str | None = Field(default=None, max_length=8000)


class ChatResponse(BaseModel):
    reply: str


class PhoneSetupRequest(BaseModel):
    area_code: str = Field(default="404", pattern=r"^\d{3}$")


def _vapi_key() -> str:
    key = os.getenv("VAPI_API_KEY", "").strip()
    if not key:
        raise HTTPException(status_code=503, detail="VAPI_API_KEY is not configured")
    return key


def _require_admin(token: str | None) -> None:
    expected = os.getenv("JARVIS_SETUP_TOKEN", "").strip()
    if not expected:
        raise HTTPException(status_code=503, detail="JARVIS_SETUP_TOKEN is not configured")
    if not token or token != expected:
        raise HTTPException(status_code=401, detail="Unauthorized")


async def _vapi(method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
    headers = {"Authorization": f"Bearer {_vapi_key()}", "Content-Type": "application/json"}
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.request(method, f"{VAPI_BASE}{path}", headers=headers, json=payload)
    if response.status_code >= 400:
        try:
            detail = response.json()
        except Exception:
            detail = response.text
        raise HTTPException(status_code=response.status_code, detail=detail)
    if not response.content:
        return None
    return response.json()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "jarvis-watch-bridge", "version": "0.3.0"}


@app.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest) -> ChatResponse:
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")

    client = OpenAI(api_key=api_key)
    context = req.health_context or "No health context supplied."
    instructions = (
        "You are JARVIS Watch Bridge, a concise personal AI assistant. "
        "Maintain a natural spoken conversational style. "
        "When health metrics are provided, treat them as wellness data only, not diagnosis. "
        "For potentially urgent symptoms or dangerous readings, advise appropriate professional or emergency care. "
        "Prefer concise answers that work well when spoken aloud or displayed on a watch."
    )
    try:
        response = client.responses.create(
            model=os.getenv("OPENAI_MODEL", "gpt-4.1-mini"),
            instructions=instructions,
            input=f"Context:\n{context}\n\nUser:\n{req.message}",
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"AI service error: {exc}") from exc
    return ChatResponse(reply=response.output_text.strip())


@app.post("/phone/setup")
async def setup_phone(
    req: PhoneSetupRequest,
    x_jarvis_admin_token: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)

    assistants = await _vapi("GET", "/assistant")
    assistant = next((item for item in assistants if item.get("name") == "JARVIS Phone Receptionist"), None)
    if not assistant:
        assistant = await _vapi(
            "POST",
            "/assistant",
            {
                "name": "JARVIS Phone Receptionist",
                "firstMessage": "Hello, you have reached Jerome's JARVIS AI assistant. I can take a message for him. May I have your name?",
                "maxDurationSeconds": 300,
                "model": {
                    "provider": "openai",
                    "model": "gpt-4o-mini",
                    "messages": [{
                        "role": "system",
                        "content": (
                            "You are JARVIS, Jerome's AI receptionist. Clearly identify yourself as an AI assistant. "
                            "Ask for the caller's name, callback number, concise reason for calling, and whether it is urgent. "
                            "Read the message back for confirmation, say Jerome will receive it, then end politely. "
                            "Never request passwords, payment card data, government ID numbers, or unnecessary sensitive information."
                        ),
                    }],
                },
            },
        )

    numbers = await _vapi("GET", "/phone-number")
    phone = next((item for item in numbers if item.get("name") == "JARVIS Free Line"), None)
    tried: list[str] = []
    if not phone:
        for area_code in dict.fromkeys([req.area_code, "531", "516", "208"]):
            tried.append(area_code)
            try:
                phone = await _vapi("POST", "/phone-number", {
                    "provider": "vapi",
                    "numberDesiredAreaCode": area_code,
                    "name": "JARVIS Free Line",
                    "assistantId": assistant["id"],
                })
                break
            except HTTPException as exc:
                if exc.status_code not in (400, 404, 409, 422):
                    raise
        if not phone:
            raise HTTPException(status_code=409, detail={"message": "No requested free number is currently available", "tried": tried})
    elif phone.get("assistantId") != assistant["id"]:
        phone = await _vapi("PATCH", f"/phone-number/{phone['id']}", {"assistantId": assistant["id"]})

    return {
        "active": True,
        "assistantId": assistant["id"],
        "phoneNumberId": phone.get("id"),
        "phoneNumber": phone.get("number"),
        "triedAreaCodes": tried,
    }


@app.get("/phone/messages")
async def phone_messages(x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    calls = await _vapi("GET", "/call")
    messages: list[dict[str, Any]] = []
    for call in calls[:25] if isinstance(calls, list) else []:
        analysis = call.get("analysis") or {}
        artifact = call.get("artifact") or {}
        customer = call.get("customer") or {}
        messages.append({
            "id": call.get("id"),
            "callerPhone": customer.get("number"),
            "summary": analysis.get("summary") or artifact.get("summary") or "Call completed.",
            "transcript": artifact.get("transcript"),
            "status": call.get("status"),
            "createdAt": call.get("createdAt"),
        })
    return {"messages": messages}
