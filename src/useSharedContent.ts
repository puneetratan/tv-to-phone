import {useEffect, useState} from 'react';
import ShareMenu from 'react-native-share-menu';

export type SharedPayload = {
  url?: string;
  text?: string;
  title?: string;
};

const URL_PATTERN = /https?:\/\/[^\s]+/i;

function parse(raw: unknown): SharedPayload | null {
  if (!raw) return null;

  // The module hands back a single item or an array depending on platform
  // and on what the sending app put on the pasteboard.
  const item: any = Array.isArray(raw) ? raw[0] : raw;
  const value: string | undefined =
    typeof item === 'string' ? item : item?.data ?? item?.value;

  if (!value || typeof value !== 'string') return null;

  const match = value.match(URL_PATTERN);
  return match ? {url: match[0]} : {text: value.trim()};
}

/**
 * Android gets this from an ACTION_SEND intent filter.
 * iOS needs a Share Extension target, which is a separate build target in
 * Xcode — this hook works on both, but the iOS side does not exist until
 * that target is created. See mobile/README.md.
 */
export function useSharedContent(): SharedPayload | null {
  const [payload, setPayload] = useState<SharedPayload | null>(null);

  useEffect(() => {
    ShareMenu.getInitialShare((item: any) => {
      const parsed = parse(item?.data ?? item);
      if (parsed) setPayload(parsed);
    });

    const listener = ShareMenu.addNewShareListener((item: any) => {
      const parsed = parse(item?.data ?? item);
      if (parsed) setPayload(parsed);
    });

    return () => listener?.remove?.();
  }, []);

  return payload;
}