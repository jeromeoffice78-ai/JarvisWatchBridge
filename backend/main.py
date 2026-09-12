import base64
import binascii
import os
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field
from openai import OpenAI

app = FastAPI(title="JARVIS Watch Bridge API", version="0.6.0")
VAPI_BASE = "https://api.vapi.ai"
JARVIS_PHONE_NUMBER = "+15318679252"
JARVIS_ASSISTANT_NAMES = ("JARVIS Phone Receptionist v2", "JARVIS Phone Receptionist")
RECENT_CALL_EVENTS: list[dict[str, Any]] = []
BOARD_MEMORY: list[str] = []


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    health_context: str | None = Field(default=None, max_length=8000)


class ChatResponse(BaseModel):
    reply: str


class BoardRequest(BaseModel):
    objective: str = Field(default="Review current priorities and recommend the next best actions.", min_length=3, max_length=2000)


class BoardResponse(BaseModel):
    briefing: str
    approval_required: bool
    status: str


class VisionRequest(BaseModel):
    image_base64: str = Field(min_length=100, max_length=6_000_000)
    prompt: str = Field(default="Describe what is visible.", min_length=1, max_length=1000)


class PhoneSetupRequest(BaseModel):
    area_code: str = Field(default="531", pattern=r"^\d{3}$")


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


def _public_base_url() -> str | None:
    value = (
        os.getenv("PUBLIC_BASE_URL", "").strip()
        or os.getenv("RENDER_EXTERNAL_URL", "").strip()
        or os.getenv("VERCEL_PROJECT_PRODUCTION_URL", "").strip()
    )
    if not value:
        return None
    if not value.startswith("http://") and not value.startswith("https://"):
        value = f"https://{value}"
    return value.rstrip("/")


def _normalize_phone(value: Any) -> str:
    return "".join(ch for ch in str(value or "") if ch.isdigit() or ch == "+")


def _watch_text(summary: str, caller: str | None, urgent: bool = False) -> str:
    lead = "🚨 URGENT CALL" if urgent else "📞 JARVIS CALL"
    who = caller or "Unknown caller"
    body = " ".join((summary or "Call completed.").split())
    text = f"{lead}: {who} — {body}"
    return text[:120]


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


async def _find_or_create_assistant() -> dict[str, Any]:
    assistants = await _vapi("GET", "/assistant")
    assistant = next(
        (item for name in JARVIS_ASSISTANT_NAMES for item in assistants if item.get("name") == name),
        None,
    )

    desired: dict[str, Any] = {
        "name": "JARVIS Phone Receptionist v2",
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
        "serverMessages": ["end-of-call-report"],
    }
    base_url = _public_base_url()
    if base_url:
        desired["server"] = {"url": f"{base_url}/vapi/webhook", "timeoutSeconds": 20}

    if not assistant:
        return await _vapi("POST", "/assistant", desired)

    patch: dict[str, Any] = {}
    if base_url:
        existing_server = assistant.get("server") or {}
        if existing_server.get("url") != desired["server"]["url"]:
            patch["server"] = desired["server"]
    if assistant.get("serverMessages") != ["end-of-call-report"]:
        patch["serverMessages"] = ["end-of-call-report"]
    if patch:
        assistant = await _vapi("PATCH", f"/assistant/{assistant['id']}", patch)
    return assistant


async def _bind_existing_phone() -> dict[str, Any] | None:
    assistant = await _find_or_create_assistant()
    numbers = await _vapi("GET", "/phone-number")
    phone = next(
        (item for item in numbers if _normalize_phone(item.get("number")) == JARVIS_PHONE_NUMBER),
        None,
    )
    if not phone:
        phone = next((item for item in numbers if item.get("name") == "JARVIS Free Line"), None)
    if not phone:
        return None
    if phone.get("assistantId") != assistant["id"]:
        phone = await _vapi("PATCH", f"/phone-number/{phone['id']}", {"assistantId": assistant["id"], "name": "JARVIS Free Line"})
    return {"assistant": assistant, "phone": phone}


def _message_from_call(call: dict[str, Any]) -> dict[str, Any]:
    analysis = call.get("analysis") or {}
    artifact = call.get("artifact") or {}
    customer = call.get("customer") or {}
    structured = artifact.get("structuredOutputs") or analysis.get("structuredData") or {}
    summary = analysis.get("summary") or artifact.get("summary") or "Call completed."
    caller_name = None
    urgency = False
    callback = customer.get("number")
    if isinstance(structured, dict):
        caller_name = structured.get("callerName") or structured.get("name")
        callback = structured.get("callbackNumber") or callback
        urgency_value = structured.get("urgent") or structured.get("urgency")
        urgency = str(urgency_value).lower() in {"true", "urgent", "high", "yes", "1"}
    return {
        "id": call.get("id"),
        "callerName": caller_name,
        "callerPhone": customer.get("number"),
        "callbackNumber": callback,
        "urgent": urgency,
        "summary": summary,
        "transcript": artifact.get("transcript") or call.get("transcript"),
        "status": call.get("status"),
        "createdAt": call.get("createdAt"),
        "endedAt": call.get("endedAt"),
        "watchText": _watch_text(summary, caller_name or callback or customer.get("number"), urgency),
    }


@app.on_event("startup")
async def startup_checks() -> None:
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    print(f"JARVIS_OPENAI_CONFIGURED={bool(api_key)}", flush=True)
    if api_key:
        try:
            test_client = OpenAI(api_key=api_key, timeout=10.0)
            test_client.responses.create(
                model=os.getenv("OPENAI_MODEL", "gpt-4.1-mini"),
                input="Reply with OK.",
                max_output_tokens=4,
            )
            print("JARVIS_OPENAI_SELFTEST=ok", flush=True)
        except Exception as exc:
            status = getattr(exc, "status_code", None)
            print(
                f"JARVIS_OPENAI_SELFTEST=failed type={type(exc).__name__} status={status}",
                flush=True,
            )

    if not os.getenv("VAPI_API_KEY", "").strip():
        return
    try:
        await _bind_existing_phone()
    except Exception as exc:
        print(f"JARVIS Vapi auto-bind warning: {type(exc).__name__}", flush=True)


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "service": "jarvis-watch-bridge",
        "version": "0.6.0",
        "openai_configured": bool(os.getenv("OPENAI_API_KEY", "").strip()),
        "model": os.getenv("OPENAI_MODEL", "gpt-4.1-mini"),
    }


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
        raise HTTPException(status_code=502, detail=f"AI service error: {type(exc).__name__}") from exc
    return ChatResponse(reply=response.output_text.strip())


@app.post("/board/run", response_model=BoardResponse)
def run_autonomous_board(req: BoardRequest) -> BoardResponse:
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")
    sensitive_terms = ("pay", "purchase", "send", "email", "sign", "file", "delete", "publish", "deploy", "account", "password", "legal", "contract", "bank", "transfer")
    approval_required = any(term in req.objective.lower() for term in sensitive_terms)
    memory = "\n".join(BOARD_MEMORY[-6:]) or "No prior board decisions."
    instructions = (
        "You are the JARVIS autonomous executive board serving Chairman Jerome Office. "
        "Internally consult these independent agents: JARVIS/orchestrator; Athena/strategy; "
        "Marcus/legal; Elena/finance; Nova/technology; Maya/growth; Victor/risk; and ARIA, "
        "the executive assistant combining operations, research, technology, finance, and risk. "
        "Each agent must assess the objective from its role, challenge weak assumptions, and converge. "
        "Return one natural spoken briefing under 450 words with each agent's named recommendation, "
        "the consensus plan, risks, and next steps. Never claim an external action was performed. "
        "Payments, purchases, legal commitments, account/security changes, messages, publishing, "
        "deployments, and deletion require explicit Chairman approval before execution."
    )
    try:
        response = OpenAI(api_key=api_key).responses.create(
            model=os.getenv("OPENAI_MODEL", "gpt-4.1-mini"), instructions=instructions,
            input=f"Board memory:\n{memory}\n\nChairman objective:\n{req.objective}", max_output_tokens=800,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"AI board error: {type(exc).__name__}") from exc
    briefing = response.output_text.strip()
    BOARD_MEMORY.append(f"{datetime.now(timezone.utc).isoformat()} | {req.objective} | {briefing[:500]}")
    del BOARD_MEMORY[:-20]
    if approval_required:
        briefing += "\n\nCHAIRMAN APPROVAL REQUIRED before any consequential action."
    return BoardResponse(briefing=briefing, approval_required=approval_required, status="awaiting_chairman_approval" if approval_required else "recommendations_ready")


@app.post("/vision", response_model=ChatResponse)
def vision(req: VisionRequest, x_jarvis_admin_token: str | None = Header(default=None)) -> ChatResponse:
    _require_admin(x_jarvis_admin_token)
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")
    try:
        image = base64.b64decode(req.image_base64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(status_code=400, detail="Invalid camera image") from exc
    if len(image) > 4_000_000:
        raise HTTPException(status_code=413, detail="Camera image is too large")
    try:
        response = OpenAI(api_key=api_key).responses.create(
            model=os.getenv("OPENAI_VISION_MODEL", os.getenv("OPENAI_MODEL", "gpt-4.1-mini")),
            instructions="You are JARVIS vision for Chairman Jerome. Describe visible facts respectfully and concisely. Never claim identity from appearance alone.",
            input=[{"role": "user", "content": [
                {"type": "input_text", "text": req.prompt},
                {"type": "input_image", "image_url": "data:image/jpeg;base64," + req.image_base64},
            ]}],
            max_output_tokens=220,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Vision service error: {type(exc).__name__}") from exc
    return ChatResponse(reply=response.output_text.strip())


@app.post("/phone/setup")
async def setup_phone(
    req: PhoneSetupRequest,
    x_jarvis_admin_token: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    assistant = await _find_or_create_assistant()
    numbers = await _vapi("GET", "/phone-number")
    phone = next((item for item in numbers if _normalize_phone(item.get("number")) == JARVIS_PHONE_NUMBER), None)
    if not phone:
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
    elif phone.get("assistantId") != assistant["id"] or phone.get("name") != "JARVIS Free Line":
        phone = await _vapi("PATCH", f"/phone-number/{phone['id']}", {"assistantId": assistant["id"], "name": "JARVIS Free Line"})

    return {
        "active": True,
        "assistantId": assistant["id"],
        "assistantName": assistant.get("name"),
        "phoneNumberId": phone.get("id"),
        "phoneNumber": phone.get("number"),
        "triedAreaCodes": tried,
        "webhook": f"{_public_base_url()}/vapi/webhook" if _public_base_url() else None,
    }


@app.post("/vapi/webhook")
async def vapi_webhook(request: Request) -> dict[str, bool]:
    payload = await request.json()
    message = payload.get("message") if isinstance(payload, dict) else None
    if not isinstance(message, dict):
        return {"ok": True}
    if message.get("type") == "end-of-call-report":
        call = dict(message.get("call") or {})
        if message.get("artifact"):
            call["artifact"] = message.get("artifact")
        if message.get("analysis"):
            call["analysis"] = message.get("analysis")
        if message.get("endedAt"):
            call["endedAt"] = message.get("endedAt")
        event = _message_from_call(call)
        event["receivedAt"] = datetime.now(timezone.utc).isoformat()
        RECENT_CALL_EVENTS.insert(0, event)
        del RECENT_CALL_EVENTS[50:]
    return {"ok": True}


@app.get("/phone/messages")
async def phone_messages(x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    calls = await _vapi("GET", "/call")
    messages = [_message_from_call(call) for call in (calls[:25] if isinstance(calls, list) else [])]
    return {"messages": messages}


@app.get("/watch/alerts")
async def watch_alerts(x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    calls = await _vapi("GET", "/call")
    alerts = [_message_from_call(call) for call in (calls[:10] if isinstance(calls, list) else [])]
    return {
        "phoneNumber": JARVIS_PHONE_NUMBER,
        "alerts": alerts,
        "recentWebhookEvents": RECENT_CALL_EVENTS[:10],
    }
