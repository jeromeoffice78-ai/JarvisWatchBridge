import hashlib
import ipaddress
import os
import socket
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urljoin, urlparse
from urllib.robotparser import RobotFileParser

import httpx
from fastapi import APIRouter, Header, HTTPException
from openai import OpenAI
from pydantic import BaseModel, HttpUrl

from knowledge import _gateway

router = APIRouter(prefix="/knowledge", tags=["knowledge-crawler"])
USER_AGENT = "JARVIS-KnowledgeBot/1.0 (+private-assistant; respects robots.txt)"
MAX_BODY_BYTES = 4 * 1024 * 1024
MAX_REDIRECTS = 5
CHUNK_SIZE = 1800
CHUNK_OVERLAP = 180


class LearnRequest(BaseModel):
    url: HttpUrl
    discoverLinks: bool = True


class HtmlExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._blocked_depth = 0
        self._in_title = False
        self.title_parts: list[str] = []
        self.text_parts: list[str] = []
        self.links: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        if tag in {"script", "style", "noscript", "svg"}:
            self._blocked_depth += 1
        if tag == "title":
            self._in_title = True
        if tag == "a" and self._blocked_depth == 0:
            href = dict(attrs).get("href")
            if href:
                self.links.append(href)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in {"script", "style", "noscript", "svg"} and self._blocked_depth > 0:
            self._blocked_depth -= 1
        if tag == "title":
            self._in_title = False

    def handle_data(self, data: str) -> None:
        clean = " ".join(data.split())
        if not clean or self._blocked_depth > 0:
            return
        if self._in_title:
            self.title_parts.append(clean)
        else:
            self.text_parts.append(clean)

    @property
    def title(self) -> str | None:
        value = " ".join(self.title_parts).strip()
        return value[:1000] if value else None

    @property
    def text(self) -> str:
        return " ".join(self.text_parts).strip()


def _require_admin(token: str | None) -> None:
    expected = os.getenv("JARVIS_SETUP_TOKEN", "").strip()
    if not expected:
        raise HTTPException(status_code=503, detail="JARVIS_SETUP_TOKEN is not configured")
    if not token or token != expected:
        raise HTTPException(status_code=401, detail="Unauthorized")


def _validate_public_url(raw: str) -> str:
    parsed = urlparse(raw)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("Only public http/https URLs are supported")
    host = parsed.hostname.lower()
    if host in {"localhost", "localhost.localdomain"} or host.endswith(".local"):
        raise ValueError("Local/private hosts are blocked")
    try:
        infos = socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80), type=socket.SOCK_STREAM)
    except socket.gaierror as exc:
        raise ValueError(f"Hostname could not be resolved: {host}") from exc
    if not infos:
        raise ValueError("Hostname resolved to no addresses")
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved or ip.is_unspecified:
            raise ValueError("Private, loopback, link-local, multicast, reserved, and unspecified addresses are blocked")
    return raw


def _safe_get(url: str, max_bytes: int = MAX_BODY_BYTES) -> tuple[httpx.Response, bytes]:
    current = _validate_public_url(url)
    headers = {"User-Agent": USER_AGENT, "Accept": "text/html,text/plain,application/xhtml+xml;q=0.9,*/*;q=0.2"}
    with httpx.Client(timeout=httpx.Timeout(20.0, connect=10.0), follow_redirects=False, headers=headers) as client:
        for _ in range(MAX_REDIRECTS + 1):
            response = client.get(current)
            if response.status_code in {301, 302, 303, 307, 308}:
                location = response.headers.get("location")
                if not location:
                    raise ValueError("Redirect response had no Location header")
                current = _validate_public_url(urljoin(current, location))
                continue
            response.raise_for_status()
            final_url = _validate_public_url(str(response.url))
            if final_url != str(response.url):
                raise ValueError("Unexpected final URL")
            data = response.content
            if len(data) > max_bytes:
                raise ValueError(f"Response exceeded {max_bytes} bytes")
            return response, data
    raise ValueError("Too many redirects")


def _robots_allowed(url: str) -> bool:
    parsed = urlparse(url)
    robots_url = f"{parsed.scheme}://{parsed.netloc}/robots.txt"
    try:
        response, data = _safe_get(robots_url, max_bytes=512 * 1024)
        text = data.decode(response.encoding or "utf-8", errors="replace")
        parser = RobotFileParser()
        parser.set_url(robots_url)
        parser.parse(text.splitlines())
        return parser.can_fetch(USER_AGENT, url)
    except Exception:
        # Fail closed for crawler access if robots policy cannot be established.
        return False


def _normalize_text(value: str) -> str:
    return " ".join(value.replace("\x00", " ").split()).strip()


def _extract(response: httpx.Response, body: bytes) -> tuple[str | None, str, list[str]]:
    content_type = response.headers.get("content-type", "").lower()
    encoding = response.encoding or "utf-8"
    text = body.decode(encoding, errors="replace")
    if "html" in content_type or "xhtml" in content_type:
        parser = HtmlExtractor()
        parser.feed(text)
        parser.close()
        return parser.title, _normalize_text(parser.text), parser.links
    if content_type.startswith("text/") or "json" in content_type:
        return None, _normalize_text(text), []
    raise ValueError(f"Unsupported content type: {content_type or 'unknown'}")


def _chunks(text: str) -> list[str]:
    if not text:
        return []
    result: list[str] = []
    start = 0
    while start < len(text):
        end = min(len(text), start + CHUNK_SIZE)
        result.append(text[start:end])
        if end >= len(text):
            break
        start = max(start + 1, end - CHUNK_OVERLAP)
    return result[:500]


def _trust_score(host: str) -> float:
    host = host.lower()
    if host.endswith(".gov") or host.endswith(".mil"):
        return 0.95
    if host.endswith(".edu"):
        return 0.88
    if host.endswith("wikipedia.org"):
        return 0.82
    return 0.55


def _embed(chunks: list[str]) -> list[list[float]]:
    key = os.getenv("OPENAI_API_KEY", "").strip()
    if not key:
        raise RuntimeError("OPENAI_API_KEY is not configured")
    model = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small").strip() or "text-embedding-3-small"
    client = OpenAI(api_key=key)
    vectors: list[list[float]] = []
    for start in range(0, len(chunks), 32):
        batch = chunks[start:start + 32]
        response = client.embeddings.create(model=model, input=batch)
        ordered = sorted(response.data, key=lambda item: item.index)
        for item in ordered:
            vector = list(item.embedding)
            if len(vector) != 1536:
                raise RuntimeError(f"Embedding dimension mismatch: {len(vector)}")
            vectors.append(vector)
    if len(vectors) != len(chunks):
        raise RuntimeError("Embedding response count mismatch")
    return vectors


def learn_url(url: str, discover_links: bool = True) -> dict[str, Any]:
    clean_url = _validate_public_url(url)
    if not _robots_allowed(clean_url):
        return {"url": clean_url, "status": "blocked", "reason": "robots.txt did not allow crawling"}

    response, body = _safe_get(clean_url)
    final_url = str(response.url)
    title, text, links = _extract(response, body)
    if len(text) < 80:
        raise ValueError("Page did not contain enough indexable text")
    chunks = _chunks(text)
    vectors = _embed(chunks)
    parsed = urlparse(final_url)
    content_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
    chunk_payload = []
    for index, (content, vector) in enumerate(zip(chunks, vectors, strict=True)):
        chunk_payload.append({
            "chunk_index": index,
            "content": content,
            "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
            "token_estimate": max(1, len(content) // 4),
            "embedding": vector,
            "metadata": {"source_url": final_url},
        })

    ingested = _gateway({
        "operation": "ingest",
        "source": {
            "url": final_url,
            "domain": parsed.hostname or "unknown",
            "title": title,
            "content_type": response.headers.get("content-type"),
            "language": "en",
            "robots_allowed": True,
            "trust_score": _trust_score(parsed.hostname or ""),
            "content_hash": content_hash,
            "metadata": {"user_agent": USER_AGENT, "http_status": response.status_code},
        },
        "chunks": chunk_payload,
    }, timeout=90.0)

    queued = 0
    if discover_links and links:
        origin_host = (parsed.hostname or "").lower()
        unique: set[str] = set()
        for href in links:
            candidate = urljoin(final_url, href).split("#", 1)[0]
            candidate_parsed = urlparse(candidate)
            if candidate_parsed.scheme not in {"http", "https"}:
                continue
            if (candidate_parsed.hostname or "").lower() != origin_host:
                continue
            if candidate in unique or candidate == final_url:
                continue
            unique.add(candidate)
            if len(unique) >= 25:
                break
        for candidate in unique:
            try:
                _gateway({"operation": "enqueue", "url": candidate, "priority": 150, "discovered_from": final_url})
                queued += 1
            except Exception:
                pass

    return {
        "url": final_url,
        "status": "learned",
        "source_id": ingested.get("source_id"),
        "chunks": len(chunks),
        "discovered_links_queued": queued,
        "title": title,
    }


@router.post("/learn")
def learn(req: LearnRequest, x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    try:
        return learn_url(str(req.url), req.discoverLinks)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Knowledge ingestion failed: {exc}") from exc


@router.post("/crawl-batch")
def crawl_batch(limit: int = 3, x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    _require_admin(x_jarvis_admin_token)
    limit = max(1, min(limit, 10))
    claimed = _gateway({"operation": "claim", "batch_size": limit})
    items = claimed.get("items") if isinstance(claimed.get("items"), list) else []
    results: list[dict[str, Any]] = []
    for item in items:
        crawl_id = str(item.get("id") or "")
        url = str(item.get("url") or "")
        if not crawl_id or not url:
            continue
        try:
            result = learn_url(url, discover_links=True)
            status = "done" if result.get("status") == "learned" else "blocked"
            _gateway({"operation": "crawl_status", "id": crawl_id, "status": status, "last_error": result.get("reason")})
            results.append(result)
        except Exception as exc:
            _gateway({"operation": "crawl_status", "id": crawl_id, "status": "failed", "last_error": str(exc)[:2000]})
            results.append({"url": url, "status": "failed", "error": str(exc)[:1000]})
    return {"claimed": len(items), "results": results}
