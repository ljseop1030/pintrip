# PinTrip Firestore Security Rules Testing Guide

본 디렉터리는 PinTrip 프로젝트의 Firestore Security Rules 단위 테스트 환경입니다.

---

## 1. 실행 방법 (PowerShell / Command Prompt)

```powershell
# 최초 의존성 설치
cd rules-tests
npm ci

# Firestore 보안 규칙 단위 테스트 실행
npm run test:emulator
```

---

## 2. 주요 Gradle 검증 명령

```powershell
# 단위 테스트 실행
.\gradlew.bat testDebugUnitTest

# 디버그 APK 빌드 검증
.\gradlew.bat assembleDebug

# 릴리스 코틀린 컴파일 검증
.\gradlew.bat compileReleaseKotlin
```
