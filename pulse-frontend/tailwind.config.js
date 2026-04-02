/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{vue,js,ts,jsx,tsx}'
  ],
  theme: {
    extend: {
      colors: {
        pulse: {
          bg: '#0a0c10',
          surface: '#12151c',
          card: '#181c25',
          border: '#2a3142',
          muted: '#4a5568',
          text: '#94a3b8',
          white: '#e2e8f0',
          alive: '#00ff41',
          warning: '#ff6b35',
          dead: '#8b0000',
          accent: '#00d4ff',
          human: '#3b82f6',
          agent: '#a855f7'
        }
      },
      fontFamily: {
        mono: ['JetBrains Mono', 'Fira Code', 'Consolas', 'monospace'],
        sans: ['Inter', 'system-ui', 'sans-serif']
      }
    }
  },
  plugins: []
}