import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'za.co.vlugboek.app',
  appName: 'Vlugboek',
  webDir: 'dist',
  server: {
    androidScheme: 'https'
  }
};

export default config;
