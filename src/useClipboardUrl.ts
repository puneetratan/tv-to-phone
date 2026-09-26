import {useEffect, useRef, useState} from 'react';
import {AppState} from 'react-native';
import Clipboard from '@react-native-clipboard/clipboard';

const URL_PATTERN = /https?:\/\/[^\s]+/i;

/**
 * Auto-loads a URL from the clipboard whenever the app is foregrounded, so
 * flicking something copied elsewhere skips the share sheet entirely: copy
 * a link anywhere, switch to Flick, it's already on the card.
 *
 * Deliberately URL-only and never plain text. A Share is an explicit
 * per-item choice; this runs on every foreground without one, so treating
 * arbitrary copied text (a password, an address) as castable would be a
 * surprising way to leak it onto a TV.
 */
export function useClipboardUrl(): string | null {
  const [url, setUrl] = useState<string | null>(null);
  const lastSeen = useRef<string | null>(null);

  useEffect(() => {
    const check = async () => {
      try {
        const text = await Clipboard.getString();
        if (!text || text === lastSeen.current) return;
        lastSeen.current = text;

        const match = text.match(URL_PATTERN);
        if (match) setUrl(match[0]);
      } catch {
        // Clipboard access failing silently is fine -- share intake still works.
      }
    };

    check();
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') check();
    });
    return () => subscription.remove();
  }, []);

  return url;
}
