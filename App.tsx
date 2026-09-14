import React, {useCallback, useEffect, useRef, useState} from 'react';
import {StatusBar, StyleSheet, Text, useWindowDimensions, View} from 'react-native';
import {
  Gesture,
  GestureDetector,
  GestureHandlerRootView,
} from 'react-native-gesture-handler';
import Animated, {
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';

import {cast} from './src/castClient';
import {startDiscovery, type Receiver} from './src/discovery';
import {useSharedContent, type SharedPayload} from './src/useSharedContent';

// Tuned for a deliberate flick, not an accidental scroll.
const MIN_FLING_VELOCITY = 1800; // px/s upward
const MIN_FLING_DISTANCE = 160; // px
const EXIT_MS = 240;

// Tap the card with nothing loaded to get something castable without
// leaving the app. Useful for the thirty-flick calibration run.
const TEST_URL = 'https://en.wikipedia.org/wiki/Iron_Man';

export default function App() {
  const {height} = useWindowDimensions();
  const shared = useSharedContent();

  const [receiver, setReceiver] = useState<Receiver | null>(null);
  const [payload, setPayload] = useState<SharedPayload | null>(null);
  const [status, setStatus] = useState('Looking for a screen on this network');

  const translateY = useSharedValue(0);
  const opacity = useSharedValue(1);
  const resetTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (shared) setPayload(shared);
  }, [shared]);

  useEffect(() => {
    const stop = startDiscovery(
      found => {
        setReceiver(found);
        setStatus(`Connected to ${found.name}`);
      },
      () => {
        setReceiver(null);
        setStatus('Looking for a screen on this network');
      },
      message => setStatus(message),
    );
    return () => {
      stop();
      if (resetTimer.current) clearTimeout(resetTimer.current);
    };
  }, []);

  const resetCard = useCallback(() => {
    translateY.value = 0;
    opacity.value = 1;
  }, [opacity, translateY]);

  const send = useCallback(() => {
    if (!receiver) {
      setStatus('No screen found yet');
      return;
    }
    if (!payload) {
      setStatus('Share something into the app first');
      return;
    }

    // Start the exit immediately. The network call is not on the critical
    // path of how this feels; the animation is.
    translateY.value = withTiming(-height * 1.6, {duration: EXIT_MS});
    opacity.value = withTiming(0, {duration: EXIT_MS});

    cast(receiver, payload).then(result => {
      setStatus(`${result.message} · ${result.roundTripMs} ms`);
      if (resetTimer.current) clearTimeout(resetTimer.current);
      resetTimer.current = setTimeout(resetCard, result.ok ? 900 : 0);
    });
  }, [height, opacity, payload, receiver, resetCard, translateY]);

  const loadTestPayload = useCallback(() => {
    setPayload({url: TEST_URL});
  }, []);

  const flick = Gesture.Pan().onEnd(event => {
    'worklet';
    const travelled = -event.translationY;
    const upward = event.velocityY < -MIN_FLING_VELOCITY && travelled > MIN_FLING_DISTANCE;
    // Reject diagonals: the flick has to be mostly vertical to count.
    const mostlyVertical = Math.abs(event.velocityY) > Math.abs(event.velocityX) * 1.5;
    if (upward && mostlyVertical) {
      runOnJS(send)();
    }
  });

  const tap = Gesture.Tap().onEnd(() => {
    'worklet';
    runOnJS(loadTestPayload)();
  });

  const cardStyle = useAnimatedStyle(() => ({
    transform: [{translateY: translateY.value}],
    opacity: opacity.value,
  }));

  const label = payload?.url ?? payload?.text ?? 'Nothing loaded yet. Tap to load a test link.';
  const hint = payload ? 'Flick up to send' : 'Share a link into this app, then flick it up';

  return (
    <GestureHandlerRootView style={styles.root}>
      <SafeAreaProvider>
        <SafeAreaView style={styles.root}>
          <StatusBar barStyle="light-content" backgroundColor="#0A0A0A" />

          <Text style={styles.status}>{status}</Text>

          <View style={styles.middle}>
            <GestureDetector gesture={Gesture.Exclusive(flick, tap)}>
              <Animated.View style={[styles.card, cardStyle]}>
                <Text style={styles.payload} numberOfLines={6}>
                  {label}
                </Text>
              </Animated.View>
            </GestureDetector>
          </View>

          <Text style={styles.hint}>{hint}</Text>
        </SafeAreaView>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#0A0A0A',
  },
  status: {
    color: '#7A766F',
    fontSize: 14,
    paddingHorizontal: 24,
    paddingTop: 16,
  },
  middle: {
    flex: 1,
    justifyContent: 'center',
  },
  card: {
    marginHorizontal: 24,
    padding: 28,
    borderRadius: 20,
    backgroundColor: '#17181A',
    shadowColor: '#000',
    shadowOpacity: 0.4,
    shadowRadius: 18,
    shadowOffset: {width: 0, height: 8},
    elevation: 10,
  },
  payload: {
    color: '#E8E6E1',
    fontSize: 18,
    lineHeight: 26,
  },
  hint: {
    color: '#7A766F',
    fontSize: 15,
    textAlign: 'center',
    paddingBottom: 32,
    paddingHorizontal: 24,
  },
});
