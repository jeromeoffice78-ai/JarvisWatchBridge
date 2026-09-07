"""Secure release-signing material broker for GitHub Actions.

The endpoint in this module never accepts a static GitHub secret. GitHub Actions must present
an OIDC identity token issued by token.actions.githubusercontent.com. The token is verified
cryptographically and restricted to this repository, the production workflow, and the main ref.
Signing material remains in the backend environment and is returned only to an authenticated,
short-lived main-branch production runner.
"""

from __future__ import annotations

import os
import threading
import time
from typing import Any

import jwt
from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import JSONResponse
from jwt import PyJWKClient

router = APIRouter(prefix="/ci", tags=["ci-signing"])

OIDC_ISSUER = "https://token.actions.githubusercontent.com"
OIDC_JWKS_URL = "https://token.actions.githubusercontent.com/.well-known/jwks"
OIDC_AUDIENCE = "jarvis-signing"
EXPECTED_REPOSITORY = "jeromeoffice78-ai/JarvisWatchBridge"
EXPECTED_OWNER = "jeromeoffice78-ai"
EXPECTED_REF = "refs/heads/main"
EXPECTED_WORKFLOW_REF = (
    "jeromeoffice78-ai/JarvisWatchBridge/.github/workflows/android-compat.yml@refs/heads/main"
)

_jwks_client = PyJWKClient(OIDC_JWKS_URL, cache_keys=True, lifespan=3600)
_seen_jti: dict[str, int] = {}
_seen_lock = threading.Lock()


def _env_required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise HTTPException(status_code=503, detail=f"Signing service is missing {name}")
    return value


def _bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise HTTPException(status_code=401, detail="GitHub OIDC bearer token required")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=401, detail="GitHub OIDC bearer token required")
    return token.strip()


def _validate_identity(token: str) -> dict[str, Any]:
    try:
        signing_key = _jwks_client.get_signing_key_from_jwt(token)
        claims = jwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256"],
            audience=OIDC_AUDIENCE,
            issuer=OIDC_ISSUER,
            options={"require": ["exp", "iat", "iss", "aud", "sub"]},
            leeway=10,
        )
    except Exception as exc:
        raise HTTPException(status_code=401, detail="Invalid GitHub OIDC identity") from exc

    if claims.get("repository") != EXPECTED_REPOSITORY:
        raise HTTPException(status_code=403, detail="Repository is not authorized for signing")
    if claims.get("repository_owner") != EXPECTED_OWNER:
        raise HTTPException(status_code=403, detail="Repository owner is not authorized")
    if claims.get("ref") != EXPECTED_REF:
        raise HTTPException(status_code=403, detail="Only the main branch may request signing material")
    if claims.get("workflow_ref") != EXPECTED_WORKFLOW_REF:
        raise HTTPException(status_code=403, detail="Workflow is not authorized for release signing")
    if claims.get("event_name") != "push":
        raise HTTPException(status_code=403, detail="Only a main-branch push may sign a release")

    exp = int(claims.get("exp", 0))
    now = int(time.time())
    if exp <= now or exp - now > 900:
        raise HTTPException(status_code=401, detail="OIDC token lifetime is not acceptable")

    # GitHub OIDC tokens normally carry a jti. If present, enforce one-time use at this broker.
    jti = str(claims.get("jti", "")).strip()
    if jti:
        with _seen_lock:
            for key, expires_at in list(_seen_jti.items()):
                if expires_at <= now:
                    _seen_jti.pop(key, None)
            if jti in _seen_jti:
                raise HTTPException(status_code=409, detail="OIDC token has already been used")
            _seen_jti[jti] = exp

    return claims


@router.post("/signing-material")
def signing_material(authorization: str | None = Header(default=None)) -> JSONResponse:
    token = _bearer_token(authorization)
    claims = _validate_identity(token)

    payload = {
        "keystore_b64": _env_required("JARVIS_RELEASE_KEYSTORE_B64"),
        "store_password": _env_required("JARVIS_RELEASE_STORE_PASSWORD"),
        "key_alias": _env_required("JARVIS_RELEASE_KEY_ALIAS"),
        "key_password": _env_required("JARVIS_RELEASE_KEY_PASSWORD"),
        "repository": claims.get("repository"),
        "ref": claims.get("ref"),
        "run_id": claims.get("run_id"),
    }
    return JSONResponse(
        content=payload,
        headers={
            "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma": "no-cache",
            "X-Content-Type-Options": "nosniff",
        },
    )
