// ESLint flat config: JS and TypeScript recommended rules, React hooks (incl. the React Compiler checks of
// eslint-plugin-react-hooks 7) and Vite fast refresh. Run with `npm run lint`; CI runs lint, typecheck and build.
import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default defineConfig([
  globalIgnores(['dist', 'dev-dist', 'coverage']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, tseslint.configs.recommended, reactHooks.configs.flat.recommended, reactRefresh.configs.vite],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    rules: {
      // The build does not use the React Compiler, so its "skipped memoization" notes (react-hook-form's watch) are noise.
      'react-hooks/incompatible-library': 'off',
      // `_`-prefixed names are deliberately unused (destructuring something away, required parameters).
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', destructuredArrayIgnorePattern: '^_', caughtErrors: 'none' },
      ],
    },
  },
]);
