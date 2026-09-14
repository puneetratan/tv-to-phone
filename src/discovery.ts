import Zeroconf from 'react-native-zeroconf';

export type Receiver = {
  name: string;
  host: string;
  port: number;
  baseUrl: string;
};

/**
 * The Pi advertises _phonecast._tcp from /etc/avahi/services/phonecast.service.
 * react-native-zeroconf sits on NsdManager (Android) and NSNetService (iOS),
 * so the same call works on both, but the iOS side needs two Info.plist keys
 * or it will silently find nothing. See mobile/README.md.
 */
export function startDiscovery(
  onFound: (receiver: Receiver) => void,
  onLost: (name: string) => void,
  onError?: (message: string) => void,
): () => void {
  const zeroconf = new Zeroconf();

  zeroconf.on('resolved', (service: any) => {
    // addresses can contain IPv6 and link-local entries; take the first IPv4.
    const ipv4: string | undefined = (service.addresses || []).find(
      (a: string) => a.includes('.') && !a.startsWith('169.254.'),
    );
    const host = ipv4 || service.host;
    if (!host || !service.port) return;

    onFound({
      name: service.name || 'Screen',
      host,
      port: service.port,
      baseUrl: `http://${host}:${service.port}`,
    });
  });

  zeroconf.on('remove', (name: string) => onLost(name));

  zeroconf.on('error', (err: any) => {
    onError?.(typeof err === 'string' ? err : 'Discovery failed');
  });

  zeroconf.scan('phonecast', 'tcp', 'local.');

  return () => {
    try {
      zeroconf.stop();
      zeroconf.removeDeviceListeners();
    } catch {
      // stopping an already-stopped scan is not worth surfacing
    }
  };
}