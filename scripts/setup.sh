#!/usr/bin/env bash
# =============================================================================
# TITAN Automation — Replit Auto-Setup
# Run once on any fresh clone/remix. Safe to re-run — skips completed steps.
# =============================================================================

BOLD="\033[1m"; GREEN="\033[32m"; YELLOW="\033[33m"; RED="\033[31m"; CYAN="\033[36m"; RESET="\033[0m"

ok()   { echo -e "${GREEN}  ✔ $*${RESET}"; }
warn() { echo -e "${YELLOW}  ⚠ $*${RESET}"; }
info() { echo -e "${CYAN}  → $*${RESET}"; }
err()  { echo -e "${RED}  ✖ $*${RESET}"; }
hdr()  { echo -e "\n${BOLD}$*${RESET}"; }

JAVA_MISSING=false
ALL_OK=true

echo -e "${BOLD}${CYAN}"
echo "╔══════════════════════════════════════════════╗"
echo "║   TITAN Automation — Environment Setup       ║"
echo "╚══════════════════════════════════════════════╝"
echo -e "${RESET}"

# ── 1. Java / JDK ─────────────────────────────────────────────────────────────
hdr "1. Java Runtime"
if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1)
    ok "Found: $JAVA_VER"
else
    err "Java not found."
    warn "Add JDK 17 in Replit: Tools → Languages, then re-run this workflow."
    JAVA_MISSING=true
    ALL_OK=false
fi

# ── 2. Git ────────────────────────────────────────────────────────────────────
hdr "2. Git"
if command -v git &>/dev/null; then
    ok "git $(git --version 2>/dev/null | awk '{print $3}')"
else
    warn "git not found — GitHub push will not work"
fi

# ── 3. Gradle Wrapper ─────────────────────────────────────────────────────────
hdr "3. Gradle Wrapper"
if [[ -f "gradlew" ]]; then
    chmod +x gradlew
    ok "gradlew present"
    if [[ -f "gradle/wrapper/gradle-wrapper.properties" ]]; then
        ok "gradle-wrapper.properties present"
    else
        mkdir -p gradle/wrapper
        cat > gradle/wrapper/gradle-wrapper.properties <<'PROPS'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
PROPS
        ok "gradle-wrapper.properties created"
    fi
else
    info "gradlew not found — creating Gradle wrapper files..."
    mkdir -p gradle/wrapper

    cat > gradle/wrapper/gradle-wrapper.properties <<'PROPS'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
PROPS
    ok "gradle-wrapper.properties written"

    info "Downloading gradlew script via curl..."
    if curl -fsSL \
        "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradlew" \
        -o gradlew 2>/dev/null; then
        chmod +x gradlew
        ok "gradlew downloaded and made executable"
    else
        warn "Could not download gradlew. Create it manually or run inside GitHub Actions."
        ALL_OK=false
    fi
fi

# ── 4. OpenCV AAR ─────────────────────────────────────────────────────────────
hdr "4. OpenCV AAR (app/libs/opencv.aar)"
OPENCV_AAR="app/libs/opencv.aar"
if [[ -f "$OPENCV_AAR" ]]; then
    SIZE=$(du -sh "$OPENCV_AAR" | cut -f1)
    ok "Local AAR found ($SIZE) — native build will use it"
else
    warn "Not present — build will use org.opencv:opencv:4.9.0 from Maven Central (automatic fallback)"
    mkdir -p app/libs
    ok "app/libs/ directory ready (add opencv.aar here for native build)"
fi

# ── 5. GitHub Push Script ─────────────────────────────────────────────────────
hdr "5. GitHub Push Script"
if [[ -f "scripts/github-push.sh" ]]; then
    chmod +x scripts/github-push.sh
    ok "scripts/github-push.sh ready"
else
    warn "scripts/github-push.sh missing"
    ALL_OK=false
fi

# ── 6. Git Remote ─────────────────────────────────────────────────────────────
hdr "6. Git Remote"
REMOTE=$(git remote get-url origin 2>/dev/null || echo "")
if [[ -n "$REMOTE" ]]; then
    ok "origin → $REMOTE"
else
    warn "No git remote set"
    info "  git remote add origin https://github.com/TITANICBHAI/titan-automation-framework.git"
fi

if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    ok "GITHUB_TOKEN secret is set"
else
    warn "GITHUB_TOKEN not set — add it in Replit: Tools → Secrets"
fi

# ── 7. Project Sanity Check ───────────────────────────────────────────────────
hdr "7. Project Structure"
declare -a REQUIRED=(
    "build.gradle.kts"
    "settings.gradle.kts"
    "gradle/libs.versions.toml"
    "app/build.gradle.kts"
    "app/src/main/cpp/CMakeLists.txt"
    "app/src/main/cpp/titan_jni.cpp"
    "app/src/main/kotlin/com/titan/automation/TitanApplication.kt"
)
for f in "${REQUIRED[@]}"; do
    if [[ -f "$f" ]]; then
        ok "$f"
    else
        err "MISSING: $f"
        ALL_OK=false
    fi
done

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}═══════════════════════════════════════════════${RESET}"
if [[ "$ALL_OK" == true ]]; then
    echo -e "${GREEN}${BOLD}  ✅  TITAN is ready to build!${RESET}"
    echo ""
    echo -e "  ${BOLD}Build APK:${RESET}       ./gradlew assembleDebug"
    echo -e "  ${BOLD}Push to GitHub:${RESET}  bash scripts/github-push.sh \"your message\""
    echo -e "  ${BOLD}Clean build:${RESET}     ./gradlew clean assembleDebug"
else
    echo -e "${YELLOW}${BOLD}  ⚠  Setup needs attention — see warnings above${RESET}"
fi
echo -e "${BOLD}═══════════════════════════════════════════════${RESET}"
echo ""
