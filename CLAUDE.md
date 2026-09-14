# CLAUDE.md

Context for Claude Code working in this repo.

## What this is

Flick a link or a screen from an Android/iOS phone onto a TV driven by a
Raspberry Pi 5. The distinguishing feature is the **gesture and spatial layer**,
not the transport. Cast, Miracast and AirPlay already solve transport. The value
here is making the handoff feel physical and directional.

If a change makes the transport better but the gesture worse, it is the wrong
change.

## Current state

**Stage 1 (plumbing) is the active stage.** Phone POSTs a URL, the Pi shows it.
No video encoding anywhere yet. Stages 2–4 are planned but not started — do not
start work on them unless explicitly asked.

## Layout

```
pi/
  server.py            FastAPI: POST /cast, POST /clear, GET /health, WS /ws
  static/receiver.html Persistent kiosk page. Never reloads.
  setup-pi.sh          venv, systemd unit, avahi service, kiosk autostart
mobile/
  App.tsx              Fling gesture, discovery wiring, exit animation
  src/discovery.ts     mDNS via react-native-zeroconf
  src/castClient.ts    POST /cast, GET /health probe
  src/useSharedContent.ts  Share-sheet intake
android/               SUPERSEDED by mobile/. Kept for reference only.
```

## Running it

Pi (systemd runs this on boot; only needed for manual debugging):

```bash
cd ~/phonecast && .venv/bin/uvicorn server:app --host 0.0.0.0 --port 8000
journalctl -u phonecast -f          # logs, including per-cast timing
avahi-browse -rt _phonecast._tcp    # confirm the Pi is advertising
```

Phone:

```bash
npx react-native start --reset-cache
npm run android        # or npm run ios (Mac only)
```

Physical device on the same 5 GHz network. Emulators and simulators cannot see
mDNS on the LAN, so discovery will always fail there.

End-to-end smoke test without the phone:

```bash
curl -X POST http://tvpi.local:8000/cast -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com"}'
```

`"screens": 0` in the response means the server is up but the kiosk page is not
connected. That is a receiver problem, not a network problem.

## Decisions that must not be silently undone

**The kiosk page is persistent.** Casts arrive over a WebSocket and mutate the
DOM. Do not change this to a page navigation per cast, however much simpler it
looks. A navigation means a white flash, a load delay, and nothing to
synchronise an entry animation against — it would make Stage 4 a rewrite.

**The fling decision and the exit animation run as worklets on the UI thread.**
Moving either to the JS thread introduces 30–80 ms of jitter under load. Stage 4
depends on the phone's exit and the TV's entry being one continuous motion, and
no clock sync rescues an animation that starts late.

**Discovery is mDNS, never a hardcoded IP.** The Pi advertises `_phonecast._tcp`
on port 8000. If discovery breaks, fix discovery.

**`sent_at_ms` goes on every cast** even though nothing consumes it yet. It is
accumulating the timing data Stage 4 will calibrate against.

## Hard constraints

- **Pi 5 has no hardware H.264 decode block.** The Pi 4 did; the Pi 5 only has
  hardware HEVC. Software H.264 at 1080p30 works but costs 30–50 ms.
- **Latency budget is ~150 ms end-to-end** for Stage 2 mirroring. Above that it
  stops feeling connected to the hand. 30–50 ms of software decode eats a third
  of the budget before transport, so Stage 2 should encode HEVC on the phone.
- **Jitter matters more than bandwidth.** The stream is ~4 Mbps. A steady 120 ms
  feels fine; 60 ms spiking to 300 ms feels broken. Prefer raw RTP over UDP to
  WebRTC on a LAN — WebRTC's adaptive jitter buffer adds 50–100 ms by design.
- **MediaProjection returns black frames for DRM content.** Netflix, Disney+,
  Prime, most banking apps. Enforced below our layer; not fixable.
- **App audio capture is opt-in by the source app** and media apps generally
  decline. Assume Stage 2 mirroring is silent.
- **iOS cannot receive.** AirPlay receiving is licensed. iOS support means
  sending only.

## Environment notes

- React Native 0.76. Since 0.76 the CLI is **not** a dependency of
  `react-native` — `@react-native-community/cli` must be in devDependencies or
  every `react-native` command fails with "command not found".
- `babel.config.js` must list `react-native-reanimated/plugin` **last**. Without
  it every `'worklet'` is silently ignored, with no error, and the UI-thread
  guarantee above quietly stops holding.
- iOS needs three Info.plist things or discovery fails silently:
  `NSLocalNetworkUsageDescription`, `NSBonjourServices` listing
  `_phonecast._tcp`, and `NSAppTransportSecurity > NSAllowsLocalNetworking`.
- iOS share intake needs a separate Xcode Share Extension target. There is no
  Android equivalent to mirror.
- Bookworm ships either Wayfire or labwc depending on the image, and kiosk
  autostart differs. `setup-pi.sh` detects which is present.

## Conventions

- Keep the dependency list short. Every added native module is another thing
  that can fail to autolink on one platform.
- No security layer exists — anything on the LAN can POST to `/cast`. Fine for a
  home network. Do not add auth unless asked; do not pretend it is secure.
- Comments explain *why*, particularly where a simpler-looking approach was
  rejected on purpose.

## Stage 1 is done when

Thirty consecutive flicks with a low rate of both misfires on ordinary scrolls
and non-registering real flicks, with the tuned values of
`MIN_FLING_VELOCITY` and `MIN_FLING_DISTANCE` written down. The question this
stage answers is whether the flick feels like throwing. If it feels like a
badly-pressed button, fix the gesture rather than moving on to encoding.

## Known Stage 1 limitations (expected, not bugs)

- Sites sending `X-Frame-Options` or restrictive `frame-ancestors` render blank
  in the kiosk iframe. Images, video files and YouTube embeds are fine.
- YouTube serves VP9/AV1 to Chromium and the Pi 5 decodes neither in hardware.
  Expect dropped frames. Routing media URLs to `mpv` is the planned fix and is
  the next task after the gesture is calibrated.