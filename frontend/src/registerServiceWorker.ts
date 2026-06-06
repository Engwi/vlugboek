import { Capacitor } from '@capacitor/core';

export function registerServiceWorker() {
  const isHttp = window.location.protocol === 'http:' || window.location.protocol === 'https:';
  if (!import.meta.env.PROD || !isHttp || Capacitor.isNativePlatform() || !('serviceWorker' in navigator)) {
    return;
  }

  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch((error) => {
      console.warn('Vlugboek service worker registration failed', error);
    });
  });
}
