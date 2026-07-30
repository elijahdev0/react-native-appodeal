/**
 * Main Appodeal SDK entry point
 * Provides access to all Appodeal functionality including ads, analytics, and events
 */

// Main Appodeal interface
import Appodeal from './RNAppodeal';

// React Native components
export { default as AppodealBanner } from './RNAppodealBanner';
export { default as AppodealMrec } from './RNAppodealMrec';
export { default as AppodealNative } from './RNAppodealNative';

// Export the main Appodeal interface
export default Appodeal;

// Export all types and enums for convenience
export {
  AppodealAdType,
  AppodealLogLevel,
  AppodealConsentStatus,
  AppodealPrivacyOptionsStatus,
  AppodealIOSPurchaseType,
  AppodealAndroidPurchaseType,
} from './types';

// Export all type definitions
export type {
  AppodealReward,
  AppodealIOSPurchase,
  AppodealAndroidPurchase,
  AppodealAdRevenue,
  AppodealNativeAdInfo,
  AppodealNativeContentType,
  AppodealNativeTemplate,
  AppodealPurchaseValidationResult,
  Map,
} from './types';

// Export event namespaces for ad event handling
export {
  AppodealSdkEvents,
  AppodealBannerEvents,
  AppodealInterstitialEvents,
  AppodealRewardedEvents,
  AppodealNativeEvents,
} from './RNAppodealEvents';
