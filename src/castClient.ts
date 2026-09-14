import type { Receiver } from './discovery';

export type CastResult = {
  ok: boolean;
  message: string;
  roundTripMs: number;
};

/**
 * Sends the payload. Timeout is deliberately short: on a LAN this either
 * works in a few milliseconds or something is wrong, and a spinner that
 * hangs for 30 seconds would undo the feeling the gesture is meant to create.
 */
export async function cast(
  receiver: Receiver,
  payload: { url?: string; text?: string; title?: string },
): Promise<CastResult> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 4000);
  const startedAt = Date.now();

  try {
    const response = await fetch(`${receiver.baseUrl}/cast`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      signal: controller.signal,
      body: JSON.stringify({
        url: payload.url ?? null,
        text: payload.text ?? null,
        title: payload.title ?? null,
        kind: 'auto',
        // Meaningless until Stage 4 syncs the clocks, but logging it from
        // day one means there is real data to calibrate against later.
        sent_at_ms: Date.now(),
      }),
    });

    const roundTripMs = Date.now() - startedAt;

    if (!response.ok) {
      return {ok: false, message: `Screen returned ${response.status}`, roundTripMs};
    }

    const body = await response.json();
    const screens: number = body?.screens ?? 0;

    if (screens === 0) {
      return {
        ok: false,
        message: 'Server is up but the TV page is not connected',
        roundTripMs,
      };
    }

    return {ok: true, message: 'Sent', roundTripMs};
  } catch (error: any) {
    const roundTripMs = Date.now() - startedAt;
    const message =
      error?.name === 'AbortError' ? 'Screen did not answer' : 'Could not reach the screen';
    return {ok: false, message, roundTripMs};
  } finally {
    clearTimeout(timeout);
  }
}

/** RTT probe. Stage 4 will use a run of these to estimate the clock offset. */
export async function probe(receiver: Receiver): Promise<number | null> {
  const startedAt = Date.now();
  try {
    const response = await fetch(`${receiver.baseUrl}/health`);
    if (!response.ok) return null;
    await response.json();
    return Date.now() - startedAt;
  } catch {
    return null;
  }
}