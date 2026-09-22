"""FastAPI receiver for tv-to-phone Stage 1.

Holds the set of connected kiosk WebSocket clients (static/receiver.html,
opened full-screen in the Pi's browser) and relays each /cast POST to all of
them over ws://<pi>:8000/ws. The kiosk page never navigates; it mutates its
own DOM in response to these messages. See CLAUDE.md's "Decisions that must
not be silently undone" for why that persistence matters.

Run with: .venv/bin/uvicorn server:app --host 0.0.0.0 --port 8000
"""

import logging
import time
import uuid
from pathlib import Path
from typing import Optional
from urllib.parse import parse_qs, urlsplit

from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("phonecast")

app = FastAPI()

STATIC_DIR = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

UPLOAD_DIR = Path(__file__).parent / "uploads"
UPLOAD_DIR.mkdir(exist_ok=True)
app.mount("/uploads", StaticFiles(directory=UPLOAD_DIR), name="uploads")

connected: set[WebSocket] = set()


class CastRequest(BaseModel):
    url: Optional[str] = None
    text: Optional[str] = None
    title: Optional[str] = None
    kind: str = "auto"
    # Meaningless until Stage 4 syncs the phone's and Pi's clocks. Recorded on
    # every cast anyway so there is real data to calibrate against later.
    sent_at_ms: Optional[int] = None


YOUTUBE_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com"}


def _youtube_embed_url(url: str) -> str:
    """Rewrites a regular YouTube watch/shorts/share link to its /embed/
    form. Regular youtube.com pages send X-Frame-Options/frame-ancestors
    headers that refuse to load in the kiosk's iframe at all -- the /embed/
    path is the one YouTube itself designed to be iframe-able. Links that
    aren't YouTube, or that YouTube doesn't have to embed, pass through
    unchanged.
    """
    parts = urlsplit(url)
    host = parts.netloc.lower()
    video_id: Optional[str] = None

    if host in YOUTUBE_HOSTS:
        if parts.path == "/watch":
            video_id = parse_qs(parts.query).get("v", [None])[0]
        elif parts.path.startswith("/shorts/"):
            video_id = parts.path.removeprefix("/shorts/").split("/")[0]
    elif host in {"youtu.be", "www.youtu.be"}:
        video_id = parts.path.lstrip("/").split("/")[0]

    if not video_id:
        return url
    return f"https://www.youtube.com/embed/{video_id}"


async def _broadcast(message: dict) -> None:
    dead = set()
    for ws in connected:
        try:
            await ws.send_json(message)
        except Exception:
            dead.add(ws)
    connected.difference_update(dead)


@app.get("/")
async def index():
    return FileResponse(STATIC_DIR / "receiver.html")


@app.get("/health")
async def health():
    return {"ok": True, "screens": len(connected)}


@app.post("/cast")
async def cast(payload: CastRequest):
    received_at_ms = int(time.time() * 1000)
    delta_ms = received_at_ms - payload.sent_at_ms if payload.sent_at_ms else None

    url = payload.url
    if url:
        embed_url = _youtube_embed_url(url)
        if embed_url != url:
            log.info("rewrote %s -> %s", url, embed_url)
            url = embed_url

    log.info(
        "cast url=%s text=%s title=%s delta_ms=%s screens=%d",
        url, payload.text, payload.title, delta_ms, len(connected),
    )

    await _broadcast({
        "type": "cast",
        "url": url,
        "text": payload.text,
        "title": payload.title,
        "kind": payload.kind,
        "sent_at_ms": payload.sent_at_ms,
        "received_at_ms": received_at_ms,
    })

    return {"ok": True, "screens": len(connected)}


@app.post("/cast-image")
async def cast_image(file: UploadFile = File(...)):
    if not (file.content_type or "").startswith("image/"):
        raise HTTPException(400, "expected an image/* upload")

    received_at_ms = int(time.time() * 1000)
    suffix = Path(file.filename or "").suffix or ".jpg"
    name = f"{uuid.uuid4().hex}{suffix}"
    (UPLOAD_DIR / name).write_bytes(await file.read())

    # Reuses the same "cast" message shape as a link -- the kiosk page
    # already knows how to point its iframe at a URL, and a browser renders
    # an image opened directly just as well as a page, so receiver.html
    # needs no changes to display a photo.
    url = f"/uploads/{name}"
    log.info("cast image=%s screens=%d", name, len(connected))

    await _broadcast({
        "type": "cast",
        "url": url,
        "text": None,
        "title": None,
        "kind": "image",
        "sent_at_ms": None,
        "received_at_ms": received_at_ms,
    })

    return {"ok": True, "screens": len(connected)}


@app.post("/clear")
async def clear():
    await _broadcast({"type": "clear"})
    return {"ok": True, "screens": len(connected)}


@app.websocket("/ws")
async def ws_endpoint(websocket: WebSocket):
    await websocket.accept()
    connected.add(websocket)
    log.info("screen connected, screens=%d", len(connected))
    try:
        while True:
            # The kiosk page never sends anything meaningful; this just
            # blocks until the socket closes so a disconnect is noticed.
            await websocket.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        connected.discard(websocket)
        log.info("screen disconnected, screens=%d", len(connected))
