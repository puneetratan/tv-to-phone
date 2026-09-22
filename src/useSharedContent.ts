import {useEffect, useState} from 'react';
import ShareMenu from 'react-native-share-menu';

export type SharedPayload = {
  url?: string;
  text?: string;
  title?: string;
  // A content:// (Android) or file:// (iOS) URI for a shared photo. Has no
  // meaning off-device, so castClient uploads its bytes instead of sending
  // it as a string.
  imageUri?: string;
  mimeType?: string;
};

const URL_PATTERN = /https?:\/\/[^\s]+/i;

function parse(raw: unknown): SharedPayload | null {
  if (!raw) return null;

  // The module hands back a single item or an array depending on platform
  // and on what the sending app put on the pasteboard.
  const item: any = Array.isArray(raw) ? raw[0] : raw;

  // Bare string shape (no mimeType alongside it) -- treat as shared text.
  if (typeof item === 'string') {
    const match = item.match(URL_PATTERN);
    return match ? {url: match[0]} : {text: item.trim()};
  }

  const mimeType: string | undefined = item?.mimeType;
  const value: string | undefined = item?.data ?? item?.value;
  if (!value || typeof value !== 'string') return null;

  // Images arrive as a mimeType + a content:// URI, never as text worth
  // regex-matching -- that URI would otherwise slip past URL_PATTERN and
  // get sent to the Pi as meaningless plain text.
  if (mimeType?.startsWith('image/')) {
    return {imageUri: value, mimeType};
  }

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
    // Pass the raw {mimeType, data} shape straight into parse() -- it needs
    // mimeType to tell an image URI apart from ordinary shared text.
    ShareMenu.getInitialShare((item: any) => {
      const parsed = parse(item);
      if (parsed) setPayload(parsed);
    });

    const listener = ShareMenu.addNewShareListener((item: any) => {
      const parsed = parse(item);
      if (parsed) setPayload(parsed);
    });

    return () => listener?.remove?.();
  }, []);

  return payload;
}