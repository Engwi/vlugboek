/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        midnight: {
          950: '#0B1623',
          900: '#102235',
          800: '#1A2D42'
        },
        championship: {
          600: '#B98734',
          500: '#C79A47',
          400: '#D4A85A'
        },
        ivory: {
          100: '#F8F6F1',
          200: '#F2EFE8'
        },
        slateInk: '#4F5B66',
        field: '#315D4E',
        burgundy: '#7B2E3B'
      },
      fontFamily: {
        display: ['Georgia', 'Cambria', 'Times New Roman', 'serif'],
        ui: ['Inter', 'Source Sans Pro', 'Segoe UI', 'Arial', 'sans-serif']
      },
      boxShadow: {
        card: '0 16px 40px rgba(11, 22, 35, 0.10)'
      }
    }
  },
  plugins: []
};
