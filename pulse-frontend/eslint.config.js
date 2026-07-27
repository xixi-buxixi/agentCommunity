import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

/**
 * ESLint configuration.
 *
 * Scope is deliberately "correctness, not style": unused variables, undefined
 * references and Vue template mistakes. The project previously had no linting at
 * all, and the bugs worth catching here are of the "component references a ref
 * that no longer exists" kind - exactly what happened when panels calling
 * non-existent endpoints were removed.
 */
export default [
  {
    ignores: ['dist/**', 'node_modules/**', 'public/**']
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.{js,mjs,vue}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.es2021
      }
    },
    rules: {
      'no-unused-vars': ['error', { args: 'after-used', ignoreRestSiblings: true }],
      // console.error is used as the terminal-style error channel across the app
      'no-console': 'off',
      'no-empty': ['error', { allowEmptyCatch: true }],
      'vue/multi-word-component-names': 'off'
    }
  },
  {
    // Test files run under Node, not in the browser.
    // Named *.test.mjs because Node 20's built-in runner only discovers that
    // pattern - it has no glob support in `node --test <pattern>`, so the CI job
    // (node 20) found zero tests while local node 23 expanded the glob and passed.
    files: ['**/*.test.mjs'],
    languageOptions: {
      globals: {
        ...globals.node
      }
    }
  }
]
