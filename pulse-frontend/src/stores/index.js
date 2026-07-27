import { createPinia } from 'pinia'

/**
 * The single pinia instance.
 *
 * It lives here rather than in main.js to break a cycle: main.js imported router,
 * which imports views, which import api modules, which import utils/request.js,
 * which imported `pinia` back from main.js. That cycle only worked by accident of
 * ESM live bindings and module evaluation order - a Vite upgrade or an SSR build
 * could evaluate request.js first and leave `pinia` undefined.
 */
export const pinia = createPinia()
