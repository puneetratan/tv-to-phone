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
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("phonecast")

app = FastAPI()

STATIC_DIR = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

connected: set[WebSocket] = set()


class CastRequest(BaseModel):
    url: Optional[str] = None
    text: Optional[str] = None
    title: Optional[str] = None
    kind: str = "auto"
    # Meaningless until Stage 4 syncs the phone's and Pi's clocks. Recorded on
    # every cast anyway so there is real data to calibrate against later.
    sent_at_ms: Optional[int] = None


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
    log.info(
        "cast url=%s text=%s title=%s delta_ms=%s screens=%d",
        payload.url, payload.text, payload.title, delta_ms, len(connected),
    )

    await _broadcast({
        "type": "cast",
        "url": payload.url,
        "text": payload.text,
        "title": payload.title,
        "kind": payload.kind,
        "sent_at_ms": payload.sent_at_ms,
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
