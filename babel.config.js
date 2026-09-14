module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    // Must be last. Without it every 'worklet' silently runs on the JS
    // thread, which is exactly the failure mode Reanimated was chosen to avoid.
    'react-native-reanimated/plugin',
  ],
};