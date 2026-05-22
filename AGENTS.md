# Repository Guidelines

## Project Structure & Module Organization

This repository is a coordination workspace. `doc/` contains business documents, `plan/` contains feature plans, and `tmp/` is temporary scratch space. Do not use `tmp/` as source context for feature design or implementation decisions. `src/` contains source code as Git submodules:

- `src/demeter_by_ai/`: Expo React Native TypeScript app.
- `src/project/`: Java 21 Spring Boot multi-module backend.

Make code changes inside the relevant submodule. Keep root-level changes limited to workspace guidance, submodule metadata, and cross-project documentation.

## Build, Test, and Development Commands

Run frontend commands from `src/demeter_by_ai/`:

- `npm run start`: start the Expo development server.
- `npm run lint`: run ESLint.
- `npm run typecheck`: run TypeScript checks.
- `npm test`: run Jest tests.
- `npm run verify`: run the project verification script.

Run backend commands from `src/project/`:

- `mvn test`: run backend tests.
- `mvn package`: build all Maven modules.
- `mvn -pl business/demeter test`: test a specific module and its dependencies when scope is narrow.

## Coding Style & Naming Conventions

Follow each submodule's local conventions. Frontend code uses TypeScript, Prettier, ESLint, `PascalCase` for components/classes, `camelCase` for functions, and `useXxx` for hooks. Backend code uses Java package paths under `work.nullptr.project`, Spring Boot conventions, DTO/VO suffixes for transport objects, and focused service/logic classes. Keep changes simple, avoid speculative abstractions, and remove unused code.

## Testing Guidelines

Frontend tests live in sibling `__tests__/` folders as `*.test.ts` or `*.test.tsx`. Backend tests live under `src/test/java`. Add or update tests for business rules, persistence behavior, API contracts, and regressions. Prefer targeted module tests for small changes, then run broader verification before handoff.

## Commit & Pull Request Guidelines

Existing history uses concise messages such as `feat: ...`, `[fix] ...`, and `build(jdk): ...`. Prefer `feat:`, `fix:`, `refactor:`, `test:`, or `build:` with a single-purpose summary. For root commits, describe workspace-level changes only. Pull requests should include scope, linked issue or plan item, verification commands, and screenshots for UI changes.

## Agent-Specific Instructions

Treat `tmp/` as disposable and exclude it from reasoning unless explicitly requested. Read `doc/` and `plan/` for product context, then modify only the relevant files under `src/`. For Figma-driven work in this project, use the configured `figma` MCP server at `http://127.0.0.1:3845/mcp`; ensure the Figma desktop app and Dev Mode MCP server are running before requesting design context. Do not commit or push unless the user explicitly asks.
