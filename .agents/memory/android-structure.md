---
name: Android project root structure
description: Critical file layout — easy to confuse with wrong root
---

- The Replit workspace root **IS** the Android project root.
- `app/build.gradle.kts` is at workspace root (not in `titan-automation/`).
- Do NOT run `pnpm dev` — this is a native Android project.
- Push via `bash scripts/github-push.sh "message"` or the **Push to GitHub** workflow in Replit panel.
- Gradle build: `./gradlew assembleDebug` from workspace root.

**Why:** Previous confusion assumed a `titan-automation/` subdirectory structure.
