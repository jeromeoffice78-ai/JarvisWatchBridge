import os
from typing import Any

import httpx
from fastapi import APIRouter, Header, HTTPException, Query
from openai import AsyncOpenAI
from pydantic import BaseModel, Field

router = APIRouter(prefix="/team", tags=["expert-team"])

SPECIALISTS: dict[str, dict[str, Any]] = {
    "athena_cross": {
        "name": "Athena Cross",
        "title": "Chief of Staff",
        "skills": ["orchestration", "delegation", "prioritization", "project management", "executive synthesis"],
        "instructions": "Coordinate specialists, define deliverables, identify dependencies, track risk, and produce decisive executive summaries.",
    },
    "marcus_vale": {
        "name": "Marcus Vale",
        "title": "Principal Software Engineer",
        "skills": ["Android", "Kotlin", "APIs", "databases", "backend engineering", "CI/CD", "debugging", "deployment"],
        "instructions": "Act as a production software engineer. Prefer testable architecture, complete implementations, secure defaults, observability, and explicit failure handling.",
    },
    "nova_reed": {
        "name": "Nova Reed",
        "title": "Cybersecurity Director",
        "skills": ["application security", "threat modeling", "privacy", "permissions", "hardening", "incident response"],
        "instructions": "Analyze security defensively. Identify attack surface, abuse cases, least-privilege controls, data protection, logging, and safe remediation.",
    },
    "orion_blake": {
        "name": "Orion Blake",
        "title": "Research Intelligence Director",
        "skills": ["web research", "fact verification", "source analysis", "competitive intelligence", "synthesis"],
        "instructions": "Separate facts from assumptions, prefer primary sources, identify uncertainty, compare conflicting evidence, and produce source-aware research conclusions.",
    },
    "victoria_kane": {
        "name": "Victoria Kane",
        "title": "Business Strategy Director",
        "skills": ["business models", "operations", "growth", "monetization", "partnerships", "go-to-market"],
        "instructions": "Focus on commercially viable strategy, execution sequence, unit economics, positioning, operating leverage, and measurable growth outcomes.",
    },
    "elias_grant": {
        "name": "Elias Grant",
        "title": "Financial Intelligence Director",
        "skills": ["budgeting", "forecasting", "pricing", "profitability", "financial modeling", "scenario analysis"],
        "instructions": "Use explicit assumptions and arithmetic. Distinguish estimates from known figures, evaluate downside cases, liquidity, profitability, and capital efficiency.",
    },
    "sophia_mercer": {
        "name": "Sophia Mercer",
        "title": "Legal Research Director",
        "skills": ["legal research", "contracts", "compliance", "case analysis", "document review", "issue spotting"],
        "instructions": "Provide careful legal research and drafting support, distinguish jurisdictions, cite controlling authority when available, flag uncertainty, and avoid pretending to be retained counsel.",
    },
    "maya_sterling": {
        "name": "Maya Sterling",
        "title": "Creative & Brand Director",
        "skills": ["branding", "advertising", "copywriting", "campaigns", "visual direction", "presentations"],
        "instructions": "Create clear, differentiated creative work aligned to audience, offer, channel, brand consistency, and measurable campaign objectives.",
    },
}


class TeamChatRequest(BaseModel):
    specialistId: str = Field(min_length=3, max_length=120)
    message: str = Field(min_length=1, max_length=6000)
    assignmentId: str | None = Field(default=None, max_length=128)


class TeamAssignmentRequest(BaseModel):
    title: str = Field(min_length=1, max_length=300)
    instructions: str = Field(min_length=1, max_length=12000)
    specialistId: str | None = Field(default=None, max_length=120)
    priority: int = Field(default=100, ge=1, le=10000)


class TeamOrchestrateRequest(BaseModel):
    task: str = Field(min_length=1, max_length=12000)
    specialistIds: list[str] | None = None


def _require_admin(token: str | None) -> None:
    expected = os.getenv("JARVIS_SETUP_TOKEN", "").strip()
    if not expected:
        raise HTTPException(status_code=503, detail="JARVIS_SETUP_TOKEN is not configured")
    if not token or token != expected:
        raise HTTPException(status_code=401, detail="Unauthorized")


def _specialist(specialist_id: str) -> dict[str, Any]:
    specialist = SPECIALISTS.get(specialist_id)
    if specialist is None:
        raise HTTPException(status_code=404, detail="Unknown specialist")
    return specialist


def _gateway_config() -> tuple[str, str]:
    url = os.getenv("JARVIS_TEAM_GATEWAY_URL", "").strip()
    token = os.getenv("JARVIS_TEAM_TOKEN", "").strip()
    if not url.startswith("https://") or not token:
        raise HTTPException(status_code=503, detail="Persistent team database is not configured")
    return url, token


async def _gateway(operation: str, **payload: Any) -> dict[str, Any]:
    url, token = _gateway_config()
    body = {"operation": operation, **payload}
    try:
        async with httpx.AsyncClient(timeout=25) as client:
            response = await client.post(url, headers={"x-jarvis-team-token": token}, json=body)
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=503, detail=f"Team database unavailable: {exc}") from exc
    if response.status_code >= 400:
        try:
            detail = response.json()
        except Exception:
            detail = response.text[:1000]
        raise HTTPException(status_code=502, detail={"team_gateway": detail})
    try:
        return response.json()
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Team database returned invalid JSON") from exc


def _model() -> str:
    return os.getenv("OPENAI_MODEL", "gpt-4.1-mini").strip() or "gpt-4.1-mini"


def _client() -> AsyncOpenAI:
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")
    return AsyncOpenAI(api_key=api_key)


def _route_specialist(text: str) -> str:
    q = text.lower()
    routes = [
        ("nova_reed", ("security", "virus", "malware", "privacy", "permission", "hack", "threat")),
        ("marcus_vale", ("code", "app", "android", "api", "database", "software", "bug", "deploy", "github")),
        ("sophia_mercer", ("legal", "law", "contract", "court", "statute", "compliance", "lawsuit")),
        ("elias_grant", ("finance", "budget", "profit", "cost", "price", "forecast", "revenue", "valuation")),
        ("maya_sterling", ("brand", "advertising", "ad campaign", "logo", "copy", "creative", "presentation")),
        ("victoria_kane", ("business", "strategy", "growth", "monetize", "market", "partnership", "sales")),
        ("orion_blake", ("research", "find", "investigate", "compare", "sources", "facts", "internet")),
    ]
    for specialist_id, keywords in routes:
        if any(keyword in q for keyword in keywords):
            return specialist_id
    return "athena_cross"


def _system_prompt(specialist_id: str) -> str:
    s = _specialist(specialist_id)
    skills = ", ".join(s["skills"])
    return (
        f"You are {s['name']}, JARVIS Expert Team {s['title']}. "
        f"Your assigned skills are: {skills}. {s['instructions']} "
        "You work for the Chairman through JARVIS. Be concise but complete. "
        "Do not claim an external action was completed unless the supplied tools or context prove it. "
        "When an assignment is executable only outside this API, produce the exact deliverable or implementation plan instead of pretending it happened."
    )


async def _generate(specialist_id: str, user_input: str, history: list[dict[str, Any]] | None = None) -> str:
    transcript = ""
    for item in (history or [])[-20:]:
        role = "Chairman" if item.get("role") == "user" else _specialist(specialist_id)["name"]
        transcript += f"{role}: {str(item.get('content', ''))[:3000]}\n"
    prompt = f"{transcript}\nChairman: {user_input}" if transcript else user_input
    try:
        response = await _client().responses.create(
            model=_model(),
            instructions=_system_prompt(specialist_id),
            input=prompt,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Specialist AI error: {exc}") from exc
    text = response.output_text.strip()
    if not text:
        raise HTTPException(status_code=502, detail="Specialist returned an empty response")
    return text


@router.get("")
async def team_catalog(x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    return {
        "orchestrator": {"id": "jarvis", "name": "JARVIS", "title": "Executive AI Orchestrator"},
        "specialists": [{"id": key, **value} for key, value in SPECIALISTS.items()],
    }


@router.post("/chat")
async def team_chat(req: TeamChatRequest, x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    specialist = _specialist(req.specialistId)
    history_data = await _gateway("list_messages", agent_id=req.specialistId, limit=30)
    history = history_data.get("messages") if isinstance(history_data.get("messages"), list) else []
    await _gateway(
        "add_message",
        agent_id=req.specialistId,
        assignment_id=req.assignmentId,
        role="user",
        content=req.message,
    )
    reply = await _generate(req.specialistId, req.message, history)
    saved = await _gateway(
        "add_message",
        agent_id=req.specialistId,
        assignment_id=req.assignmentId,
        role="agent",
        content=reply,
    )
    return {
        "specialist": {"id": req.specialistId, **specialist},
        "reply": reply,
        "message": saved.get("message"),
    }


@router.post("/assign")
async def assign(req: TeamAssignmentRequest, x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    specialist_id = req.specialistId or _route_specialist(f"{req.title}\n{req.instructions}")
    specialist = _specialist(specialist_id)
    created = await _gateway(
        "create_assignment",
        title=req.title,
        instructions=req.instructions,
        assigned_agent=specialist_id,
        priority=req.priority,
    )
    assignment = created.get("assignment") or {}
    assignment_id = str(assignment.get("id", ""))
    if not assignment_id:
        raise HTTPException(status_code=502, detail="Assignment database returned no assignment id")

    await _gateway("update_assignment", id=assignment_id, status="running")
    await _gateway(
        "add_message",
        agent_id=specialist_id,
        assignment_id=assignment_id,
        role="system",
        content=f"Assignment received: {req.title}",
    )
    try:
        result = await _generate(
            specialist_id,
            f"ASSIGNMENT TITLE: {req.title}\n\nINSTRUCTIONS:\n{req.instructions}\n\nComplete this assignment now and return the finished work product or the strongest executable result possible.",
        )
        completed = await _gateway("update_assignment", id=assignment_id, status="completed", result=result)
        await _gateway(
            "add_message",
            agent_id=specialist_id,
            assignment_id=assignment_id,
            role="agent",
            content=result,
        )
        return {"specialist": {"id": specialist_id, **specialist}, "assignment": completed.get("assignment")}
    except HTTPException as exc:
        await _gateway("update_assignment", id=assignment_id, status="failed", error=str(exc.detail)[:4000])
        raise


@router.post("/orchestrate")
async def orchestrate(req: TeamOrchestrateRequest, x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    requested = req.specialistIds or [_route_specialist(req.task), "athena_cross"]
    selected: list[str] = []
    for specialist_id in requested:
        if specialist_id in SPECIALISTS and specialist_id not in selected:
            selected.append(specialist_id)
    if "athena_cross" not in selected:
        selected.append("athena_cross")
    selected = selected[:4]

    expert_outputs: list[dict[str, str]] = []
    for specialist_id in selected:
        if specialist_id == "athena_cross":
            continue
        output = await _generate(specialist_id, f"Analyze this team assignment from your specialty and produce your contribution:\n\n{req.task}")
        expert_outputs.append({"specialistId": specialist_id, "name": SPECIALISTS[specialist_id]["name"], "output": output})

    evidence = "\n\n".join(f"{item['name']}:\n{item['output']}" for item in expert_outputs)
    synthesis = await _generate(
        "athena_cross",
        f"Chairman task:\n{req.task}\n\nSpecialist work:\n{evidence or 'No specialist draft was requested.'}\n\nSynthesize the final coordinated answer, resolve conflicts, identify the next executable actions, and assign responsibility by specialist name.",
    )
    return {"task": req.task, "specialists": expert_outputs, "chiefOfStaff": synthesis}


@router.get("/assignments")
async def assignments(
    specialistId: str | None = Query(default=None, max_length=120),
    status: str | None = Query(default=None, max_length=32),
    limit: int = Query(default=50, ge=1, le=100),
    x_jarvis_admin_token: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    payload: dict[str, Any] = {"limit": limit}
    if specialistId:
        _specialist(specialistId)
        payload["assigned_agent"] = specialistId
    if status:
        payload["status"] = status
    return await _gateway("list_assignments", **payload)
