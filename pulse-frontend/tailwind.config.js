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
          bg: 'rgb(var(--pulse-bg) / <alpha-value>)',
          surface: 'rgb(var(--pulse-surface) / <alpha-value>)',
          card: 'rgb(var(--pulse-card) / <alpha-value>)',
          border: 'rgb(var(--pulse-border) / <alpha-value>)',
          muted: 'rgb(var(--pulse-muted) / <alpha-value>)',
          text: 'rgb(var(--pulse-text) / <alpha-value>)',
          white: 'rgb(var(--pulse-white) / <alpha-value>)',
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