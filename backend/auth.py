import base64
import hashlib
import hmac
import json
import os
import threading
import time
from collections import defaultdict, deque
from typing import Any

from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, Field

router = APIRouter(prefix="/auth", tags=["chairman-auth"])
TOKEN_PREFIX = "jv1"
DEFAULT_TTL_DAYS = 180
ENROLL_WINDOW_SECONDS = 15 * 60
ENROLL_MAX_ATTEMPTS = 5
_attempts: dict[str, deque[float]] = defaultdict(deque)
_attempt_lock = threading.Lock()


class EnrollRequest(BaseModel):
    pairingCode: str = Field(min_length=8, max_length=256)
    deviceId: str = Field(min_length=3, max_length=256)
    deviceName: str = Field(min_length=1, max_length=256)


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def _auth_secret() -> bytes:
    value = os.getenv("JARVIS_DEVICE_AUTH_SECRET", "").strip()
    if len(value) < 32:
        raise HTTPException(status_code=503, detail="Device authentication is not configured")
    return value.encode("utf-8")


def _token_ttl_seconds() -> int:
    raw = os.getenv("JARVIS_DEVICE_TOKEN_TTL_DAYS", str(DEFAULT_TTL_DAYS)).strip()
    try:
        days = int(raw)
    except ValueError:
        days = DEFAULT_TTL_DAYS
    return max(1, min(days, 365)) * 86400


def _sign_payload(payload_segment: str) -> str:
    signature = hmac.new(_auth_secret(), f"{TOKEN_PREFIX}.{payload_segment}".encode("utf-8"), hashlib.sha256).digest()
    return _b64url_encode(signature)


def issue_device_token(device_id: str, device_name: str) -> tuple[str, int]:
    now = int(time.time())
    expires = now + _token_ttl_seconds()
    payload = {
        "sub": device_id,
        "name": device_name[:256],
        "scope": "chairman",
        "iat": now,
        "exp": expires,
        "v": 1,
    }
    payload_segment = _b64url_encode(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    token = f"{TOKEN_PREFIX}.{payload_segment}.{_sign_payload(payload_segment)}"
    return token, expires


def verify_device_token(token: str) -> dict[str, Any] | None:
    if not token.startswith(f"{TOKEN_PREFIX}.") or len(token) > 4096:
        return None
    parts = token.split(".")
    if len(parts) != 3 or parts[0] != TOKEN_PREFIX:
        return None
    payload_segment, signature = parts[1], parts[2]
    try:
        expected = _sign_payload(payload_segment)
    except HTTPException:
        return None
    if not hmac.compare_digest(signature, expected):
        return None
    try:
        payload = json.loads(_b64url_decode(payload_segment))
    except Exception:
        return None
    if not isinstance(payload, dict):
        return None
    if payload.get("scope") != "chairman" or payload.get("v") != 1:
        return None
    try:
        expires = int(payload.get("exp", 0))
        issued = int(payload.get("iat", 0))
    except (TypeError, ValueError):
        return None
    now = int(time.time())
    if issued <= 0 or expires <= now or issued > now + 300:
        return None
    subject = str(payload.get("sub") or "").strip()
    if len(subject) < 3 or len(subject) > 256:
        return None
    return payload


def require_access(token: str | None) -> dict[str, Any]:
    """Accept server admin credentials or an expiring signed Chairman device session."""
    if token:
        device_payload = verify_device_token(token)
        if device_payload is not None:
            return device_payload

    legacy_admin = os.getenv("JARVIS_SETUP_TOKEN", "").strip()
    if legacy_admin and token and hmac.compare_digest(token, legacy_admin):
        return {"sub": "server-admin", "scope": "server-admin"}
    raise HTTPException(status_code=401, detail="Unauthorized")


def _enrollment_rate_limit(request: Request) -> None:
    forwarded = request.headers.get("x-forwarded-for", "").split(",", 1)[0].strip()
    client_key = forwarded or (request.client.host if request.client else "unknown")
    now = time.monotonic()
    with _attempt_lock:
        bucket = _attempts[client_key]
        cutoff = now - ENROLL_WINDOW_SECONDS
        while bucket and bucket[0] < cutoff:
            bucket.popleft()
        if len(bucket) >= ENROLL_MAX_ATTEMPTS:
            raise HTTPException(status_code=429, detail="Too many enrollment attempts; try again later")
        bucket.append(now)


@router.post("/enroll")
def enroll(req: EnrollRequest, request: Request) -> dict[str, Any]:
    _enrollment_rate_limit(request)
    expected = os.getenv("JARVIS_ENROLLMENT_CODE", "").strip()
    if len(expected) < 8:
        raise HTTPException(status_code=503, detail="Chairman enrollment is not configured")
    if not hmac.compare_digest(req.pairingCode, expected):
        raise HTTPException(status_code=401, detail="Invalid pairing code")

    token, expires = issue_device_token(req.deviceId.strip(), req.deviceName.strip())
    return {
        "token": token,
        "scope": "chairman",
        "deviceId": req.deviceId.strip(),
        "expiresAt": expires,
    }


@router.get("/session")
def session(x_jarvis_admin_token: str | None = Header(default=None)) -> dict[str, Any]:
    payload = require_access(x_jarvis_admin_token)
    return {
        "authenticated": True,
        "scope": payload.get("scope"),
        "deviceId": payload.get("sub"),
        "expiresAt": payload.get("exp"),
    }
