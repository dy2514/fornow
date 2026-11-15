# 잠깐만 (ForNow) 0.3.0

공조와 편의 설정을 잠시 바꾸고, 사용자가 직접 바꾼 항목을 보호하면서 원래 설정으로 되돌리는 Pleos 차량용 앱입니다.

## APK 및 설치
- 출품팀: PAL223
- APK: [apk/PAL223_잠깐만_release.apk](apk/PAL223_잠깐만_release.apk)
- 패키지: `ai.pleos.fornow`
- 버전: `0.3.0` / versionCode `4`
- SHA-256: `1555b927427cb00fbbe6c904e684552720bed06f172ae98317c5b55b372fa5a8`

Pleos Connect 에뮬레이터를 실행하고 Android SDK Platform Tools의 adb를 PATH에 등록합니다.
저장소 루트에서 다음을 실행합니다. 이미 같은 패키지의 다른 서명 APK가 설치되어 있다면
기존 앱과 제출 APK의 서명이 달라 업데이트가 실패할 수 있습니다. 테스트 데이터를 보관한 뒤 기존 설치를 정리하거나 깨끗한 에뮬레이터를 이용합니다.

```sh
adb devices
adb install -r "apk/PAL223_잠깐만_release.apk"
adb shell am start -n ai.pleos.fornow/.MainActivity
```

여러 기기가 연결되어 있다면 `adb -s 기기ID`로 대상 기기를 지정합니다.
일반 휴대전화용 앱이 아니라 Android Automotive와 Pleos 차량 서비스를 전제로 합니다.

## 실행 및 필요한 설정
1. 홈의 **ForNow** 아이콘을 실행합니다. 출품명은 ‘잠깐만’이며 설치 후 앱 표시는 ForNow입니다.
2. P단에서 최초 권한 요청을 처리하고 기능·시간을 선택해 적용하거나, 통화·창구 자동 모드를 켭니다.
3. 종료 시 ‘지금 되돌리기’ 또는 ‘이대로 유지’를 선택합니다.
4. Gleo 연동 환경의 시험 발화는 “잠깐만, 1분만 바람 줄여줘”입니다. 실제 스키마 등록·승인과 음성 인식은 별도 확인해야 합니다.
5. 강제 종료·재부팅 후 자동 복원을 보장하지 않으며 오래된 설정을 재적용하지 않습니다.

차량 기능별 지원 여부와 권한, P단 및 Car UX 제한을 확인해야 합니다.
권한을 강제로 부여하는 우회 절차는 사용하지 않습니다.

## 포함 기능과 한계
- 1·3·5분 동안 풍량과 지원되는 좌석·핸들 열선 등의 항목을 일시 조절합니다.
- 통화·창문·속도 조건을 사용하는 자동 모드는 P단에서 켭니다.
- 즉시 복원 또는 현재 값 유지가 가능하며 사용자가 직접 바꾼 항목은 복원에서 제외합니다.
- 미디어 전용 음소거는 비활성 상태입니다. 기본 “잠깐만” 단독 호출과 후속 지시 확장은 이 APK의 구현 범위와 구분해야 합니다.

## 개발 및 테스트 환경
원본 패키지에 기록된 개발 대상 환경입니다. 이번 재패키징 작업에서 에뮬레이터를 실행해 확인한 값은 아닙니다.

| 항목 | 값 |
| --- | --- |
| Pleos Connect 에뮬레이터 | 2.1.0 / AVD Pleos_Coach |
| Android / ABI | API 34 / x86_64 |
| 화면 | 2560 × 1440 px / 가로 / 160 dpi |
| JDK / Kotlin | 17 / 2.0.0 |
| Android Gradle Plugin / Gradle | 8.8.2 / 8.10.2 |
| compileSdk / minSdk / targetSdk | 35 / 33 / 34 |
| Android Build Tools | 35.0.0 |
| Pleos SDK | Vehicle 2.3.2 |

## 빌드 및 검사
JDK 17과 Android SDK 35를 설치하고 `local.properties`의 `sdk.dir`에 SDK 경로를 설정합니다.
필요한 의존성 저장소는 `settings.gradle.kts`에 선언되어 있습니다.

```sh
./gradlew :fornow:assembleDebug
./gradlew :fornow-core:test :fornow:testDebugUnitTest :fornow:lintDebug
```

Windows에서는 `gradlew.bat` 또는 `BUILD_FORNOW.cmd`를 사용합니다.
기존 BUILD 파일은 debug 빌드·검사용이며 제출 APK를 새로 만드는 명령이 아닙니다.

release 재빌드는 `signing.properties.example`을 참고해 본인의 서명 키 설정을 만든 뒤
`gradlew.bat :fornow:assembleRelease`를 실행합니다.
산출물은 `fornow/build/outputs/apk/release/`에서 확인합니다.
서명 키와 비밀번호는 포함하지 않았습니다. 기존 제출 APK의 서명을 유지하려면 기존 키가 필요합니다.

원본 검증 기록에는 단위 테스트 85개, 실패·오류 0개와 lint 오류 0개가 기록되어 있습니다.
기존 실행 환경에서 Gradle 테스트 프로세스 연결 문제가 있어 JUnitCore로 검사했다는 기록입니다.
이는 원본 기록이며 이번 패키징에서 테스트를 재실행한 결과가 아닙니다.

이번 패키징에서는 APK 파일 해시, v2 서명과 서명된 내용의 무결성, manifest 버전과 debuggable 비활성 상태를 확인했습니다.
**에뮬레이터 설치·실행, 실제 음성·차량 제어·내비게이션 동작은 미검증입니다.**

## 소스 구성
- `fornow/`: Android 화면·서비스·Pleos 연동
- `fornow-core/`: 판단 및 상태 관리 코어
- `apk/`: 제출 release APK
- `licenses/`: 포함 글꼴의 라이선스

이 폴더에는 소스코드·빌드 파일, release APK, README와 포함 글꼴의 라이선스가 있습니다.
이 폴더의 내용을 앱별 GitHub 저장소 루트에 올리거나, 폴더를 압축해 내려받을 수 있는 링크로 제출할 수 있습니다.
