# PinTrip Local Verification & Security Rules Testing Guide

본 디렉터리는 PinTrip 프로젝트의 Firestore Security Rules 단위 테스트 및 로컬 에뮬레이터 통합 검증 환경입니다.

---

## 1. 빌드 변체 (Build Variants) 안내

- **`fullDebug` / `fullRelease`**: 기존 일반/운영 앱 (`com.pintrip.app`). 실제 Firebase 서비스 및 `google-services.json` 사용.
- **`demoDebug` / `demoRelease`**: 로컬 에뮬레이터 검증 전용 앱 (`com.pintrip.app.demo`). 비밀값 없는 `demo-pintrip` 설정으로 시작되며, `10.0.2.2:9099`(Auth) 및 `10.0.2.2:8080`(Firestore) 에뮬레이터에 자동 연결됩니다.

---

## 2. 실행 방법 (PowerShell / Command Prompt)

### (A) Firestore Security Rules 단위 테스트 실행
프로젝트 루트 또는 `rules-tests` 디렉터리에서 실행합니다:

```powershell
# 프로젝트 루트에서 실행 시
npm --prefix rules-tests run test:emulator

# rules-tests 디렉터리에서 실행 시
cd rules-tests
npm run test:emulator
```

---

### (B) 로컬 Firebase 에뮬레이터 구동 및 demoDebug 앱 통합 테스트

#### 1단계: 로컬 Firebase 에뮬레이터 실행 (터미널 1)
```powershell
npm --prefix rules-tests run start:integration
```

#### 2단계: Android 에뮬레이터 연결 확인 (터미널 2)
```powershell
adb devices
```

#### 3단계: demoDebug 앱 빌드 및 설치
```powershell
# Windows PowerShell 기준
.\gradlew.bat installDemoDebug

# demoDebug 앱 실행
adb shell am start -n com.pintrip.app.demo/com.pintrip.app.MainActivity
```

---

## 3. 주요 Gradle 검증 명령

```powershell
# 단위 테스트 실행
.\gradlew.bat testDemoDebugUnitTest
.\gradlew.bat testFullDebugUnitTest

# APK 빌드 검증
.\gradlew.bat assembleDemoDebug
.\gradlew.bat assembleFullDebug

# Release Kotlin 컴파일 검증
.\gradlew.bat compileFullReleaseKotlin
```
