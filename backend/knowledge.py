import os
from typing import Any

import httpx
from fastapi import APIRouter, Header, HTTPException, Query
from openai import OpenAI
from auth import require_access

router = APIRouter(prefix="/knowledge", tags=["knowledge"])


def _require_admin(token: str | None) -> None:
    require_access(token)

def _config() -> tuple[str, str]:
    url = os.getenv("JARVIS_KNOWLEDGE_GATEWAY_URL", "").strip()
    token = os.getenv("JARVIS_KNOWLEDGE_TOKEN", "").strip()
    if not url.startswith("https://") or not token:
        raise RuntimeError("Knowledge database is not configured")
    return url, token


def _gateway(payload: dict[str, Any], timeout: float = 25.0) -> dict[str, Any]:
    url, token = _config()
    response = httpx.post(
        url,
        headers={"x-jarvis-knowledge-token": token, "content-type": "application/json"},
        json=payload,
        timeout=timeout,
    )
    if response.status_code >= 400:
        raise RuntimeError(f"Knowledge gateway {response.status_code}: {response.text[:800]}")
    data = response.json()
    if not isinstance(data, dict):
        raise RuntimeError("Knowledge gateway returned invalid payload")
    return data


def _embedding(client: OpenAI, query: str) -> list[float]:
    model = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small").strip() or "text-embedding-3-small"
    response = client.embeddings.create(model=model, input=query[:4000])
    vector = list(response.data[0].embedding)
    if len(vector) != 1536:
        raise RuntimeError(f"Knowledge embedding dimension mismatch: {len(vector)}")
    return vector


def search(query: str, client: OpenAI, match_count: int = 8) -> list[dict[str, Any]]:
    clean = query.strip()
    if not clean:
        return []
    vector = _embedding(client, clean)
    data = _gateway({
        "operation": "search",
        "query_text": clean[:4000],
        "embedding": vector,
        "match_count": max(1, min(match_count, 20)),
        "semantic_weight": 0.72,
    })
    results = data.get("results")
    return results if isinstance(results, list) else []


def retrieve_context(query: str, client: OpenAI, max_chars: int = 7000) -> str:
    """Return source-attributed RAG context. Any failure is non-fatal to normal JARVIS chat."""
    try:
        results = search(query, client, match_count=8)
    except Exception as exc:
        print(f"JARVIS knowledge retrieval warning: {exc}")
        return ""
    if not results:
        return ""

    lines = ["JARVIS CLOUD KNOWLEDGE (retrieved; verify freshness for time-sensitive claims):"]
    used = len(lines[0])
    for item in results:
        if not isinstance(item, dict):
            continue
        content = " ".join(str(item.get("content") or "").split())[:1800]
        if not content:
            continue
        title = " ".join(str(item.get("title") or "Untitled source").split())[:300]
        url = str(item.get("url") or "")[:1500]
        fetched = str(item.get("fetched_at") or "")[:80]
        score = item.get("score")
        entry = f"- {title} | {url} | fetched={fetched} | score={score}: {content}"
        if used + len(entry) + 1 > max_chars:
            break
        lines.append(entry)
        used += len(entry) + 1
    return "\n".join(lines) if len(lines) > 1 else ""


@router.get("/stats")
def stats(x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    try:
        return _gateway({"operation": "stats"})
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"Knowledge database unavailable: {exc}") from exc


@router.get("/search")
def search_endpoint(
    q: str = Query(min_length=1, max_length=4000),
    limit: int = Query(default=8, ge=1, le=20),
    x_jarvis_admin_token: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")
    try:
        results = search(q, OpenAI(api_key=api_key), match_count=limit)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"Knowledge search failed: {exc}") from exc
    return {"query": q, "results": results}
