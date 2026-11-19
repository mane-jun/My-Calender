# My-Calender 개발 계획

**목표** · 갤럭시 홈 화면에서 과제와 일정을 추가·수정·완료하고, 같은 데이터를 노션과 Windows에서 확인한다. 갤럭시 앱을 다시 설치해도 노션에 저장된 항목을 불러올 수 있어야 한다.

**구조** · 갤럭시 앱은 `일정` 데이터베이스에 Notion API로 직접 연결한다. Android에는 마지막으로 읽은 목록과 저장을 마치지 못한 초안을 보관한다. Windows는 같은 데이터베이스의 노션 보기를 사용한다.

**기술 구성** · Kotlin, Jetpack Compose, Jetpack Glance, Room, WorkManager, Notion API, Android Keystore. 개인 갤럭시에서 직접 설치해 사용하는 구성을 기준으로 잡고, 노션 무료 워크스페이스와 별도 서버 없는 구성을 유지한다.

## 1. 첫 버전에서 사용할 화면

| 화면 | 동작 |
| --- | --- |
| 갤럭시 홈 화면 위젯 | 미완료 항목을 스크롤해 보고 바로 완료한다. 별표와 날짜·시간을 표시한다. `+`로 추가창을 열고 제목을 눌러 수정한다. 새로고침을 누르면 노션에서 목록을 다시 불러온다. |
| 갤럭시 추가·수정창 | 제목, 날짜, 시간 또는 종일, 중요 여부를 입력한다. 제목은 필수이며 날짜는 비워둘 수 있다. 입력창에서 저장 결과를 확인한다. |
| 노션 `예정` 보기 | 날짜순으로 미완료 항목을 확인하고 수정한다. 날짜가 미정인 항목도 확인할 수 있게 구성한다. |
| 노션 `월간` 보기 | `날짜` 속성이 있는 항목을 월간 달력에 표시한다. 갤럭시와 Windows에서 같은 보기를 연다. |
| Windows 화면 | 노션의 `예정` 보기를 작은 창으로 열고 필요할 때 PowerToys로 고정한다. |

위젯 목록의 날짜 구간은 `기한 지남 → 오늘 → 내일 → 7일 이내 → 이후 → 날짜 미정`으로 잡는다. `7일 이내`에는 모레부터 오늘 기준 7일 후까지를 넣고 빈 구간은 숨긴다. 구간은 `Asia/Seoul` 날짜를 기준으로 판정한다. 같은 날에는 종일 항목을 먼저 두고 시간순으로 표시한다. ⭐는 해당 항목에 표시한다.

삼성 캘린더 연동, 홈 화면 월간 위젯, 시간 알림과 반복 일정, 실제 Windows 바탕화면 위젯은 후속 후보로 둔다. 첫 버전의 달력 화면은 노션의 월간 보기다.

## 2. 노션 데이터 구조

| 속성 | 노션 유형 | 사용 방식 |
| --- | --- | --- |
| `이름` | 제목 | 과제명이나 일정명 |
| `날짜` | 날짜 | 미정은 비워둔다. 종일은 날짜만, 시간이 있으면 시간대가 포함된 값으로 저장한다. |
| `상태` | 상태 | `할 일`과 `완료`를 사용한다. 연결 테스트에서 두 옵션을 확인한다. |
| `중요` | 체크박스 | 별표 표시 |
| `기록 ID` | 텍스트 | 앱에서 만든 항목에 UUID를 부여한다. 응답이 끊겨도 같은 항목이 이미 등록됐는지 조회할 수 있다. 사람이 직접 추가한 기존 항목은 이 값이 비어 있어도 표시한다. |

API 요청에는 `Notion-Version: 2026-03-11`을 사용한다. 조회 시 노션 페이지 ID도 보관한다. 기존 항목 수정과 완료에는 이 ID를 사용한다. 기존 항목을 수정할 때는 사용자가 실제로 변경한 속성만 전송하고, 완료 체크는 `상태`만 바꾼다. 실데이터를 쓰기 전에 별도 테스트 DB에서 읽기·추가·수정이 모두 되는지 확인한다.

## 3. 개발 순서

### 1단계 · 갤럭시 설치 및 노션 연결

- [ ] Android Studio로 Kotlin 프로젝트를 만들고 개발용 빌드를 갤럭시에 설치한다. 실제 기기에 설치한 뒤 Android 버전과 홈 화면 위젯 크기를 확인한다.
- [ ] `일정 테스트` DB를 만들고 내부 연결에 읽기·추가·수정 권한을 부여한다. 연결은 이 DB에만 공유한다.
- [ ] 설정 화면에서 `data_source_id`와 토큰을 입력받아 속성 유형과 `상태` 옵션을 확인한다. 토큰은 Android Keystore의 키로 암호화해 기기에 저장한다. 토큰을 소스, APK 기본값, 로그에 기록하지 않고 앱 백업에서도 제외한다.
- [ ] 연결 완료 기준은 유효한 설정에서 테스트 항목을 읽고, 토큰 오류와 DB 권한 오류에 서로 다른 안내를 보여주는 것이다.

예정 파일 `app/src/main/java/com/mycalender/notion/NotionApi.kt`, `app/src/main/java/com/mycalender/settings/ConnectionScreen.kt`, `app/src/main/java/com/mycalender/settings/ConnectionStore.kt`

### 2단계 · 조회와 위젯 목록

- [ ] `TaskRepository.refresh()`에서 미완료 목록을 끝 페이지까지 읽은 뒤 Room에 한 번에 반영한다. 중간 페이지 조회가 실패하면 기존 목록과 마지막 성공 시각을 유지한다. 호출 제한 응답은 `Retry-After`를 따른다.
- [ ] `groupTasks(tasks, today)`로 날짜 구간과 정렬을 계산한다. 날짜 없음, 자정 전후, 종일과 시간 지정 항목, 정확히 7일 후와 8일 후를 검증한다.
- [ ] Glance로 홈 화면에 목록과 수동 새로고침을 붙인다. 네트워크가 끊기면 마지막 목록과 갱신이 지연된 상태를 표시한다.
- [ ] 화면 완료 기준은 실제 갤럭시 위젯에서 PC에 만든 항목을 수동 새로고침으로 확인하고, 네트워크가 끊겨도 직전 목록을 읽을 수 있는 것이다.

예정 파일 `app/src/main/java/com/mycalender/tasks/TaskRepository.kt`, `app/src/main/java/com/mycalender/tasks/TaskCache.kt`, `app/src/main/java/com/mycalender/tasks/TaskGrouping.kt`, `app/src/main/java/com/mycalender/widget/ScheduleWidget.kt`

### 3단계 · 추가, 수정, 완료

- [ ] `TaskRepository.create(draft)`를 추가한다. 사용자가 저장하면 초안과 `기록 ID`를 로컬에 먼저 기록하고, 같은 동작이 실행 중일 때 중복 탭을 막는다. 노션에 저장되면 초안을 정리하고 위젯을 갱신한다.
- [ ] 생성 요청의 결과가 불확실하면 같은 `기록 ID`를 조회한다. 이미 저장된 행이 발견되면 기존 행을 사용한다. 결과를 검증할 수 없는 경우에는 초안과 오류 상태를 유지하고 자동으로 생성 요청을 반복하지 않는다. 사용자가 재시도를 선택해도 먼저 같은 ID를 다시 조회한다.
- [ ] `TaskRepository.update(pageId, changes)`를 추가하고 입력창과 완료 버튼에 연결한다. 완료 요청에 실패하면 항목을 목록에 남기고, 수정 요청에 실패하면 입력값을 보존한다. 달력의 종일 항목이 시간 지정 항목으로 바뀌거나 하루 앞뒤로 이동하지 않는지 확인한다.
- [ ] 조작 완료 기준은 갤럭시에서 추가·날짜 변경·완료한 결과가 노션과 PC에 보이고, 저장 버튼을 반복 탭하거나 성공 응답을 잃어도 앱이 항목을 자동으로 중복 생성하지 않는 것이다.

예정 파일 `app/src/main/java/com/mycalender/tasks/TaskRepository.kt`, `app/src/main/java/com/mycalender/tasks/TaskCache.kt`, `app/src/main/java/com/mycalender/editor/TaskEditorActivity.kt`, `app/src/main/java/com/mycalender/widget/ScheduleWidget.kt`

### 4단계 · PC 반영, 복구, 실제 사용 전환

- [ ] WorkManager로 30분 간격의 백그라운드 갱신을 요청한다. Android 절전 상태에서는 실행 시각이 달라질 수 있으므로 `마지막 갱신 시각`과 수동 새로고침을 제공한다. 갤럭시에서 저장한 변경은 성공 응답을 받은 직후 위젯에 반영한다.
- [ ] 같은 DB에 노션 `예정` 및 `월간` 보기를 구성하고, Windows에 `예정` 화면을 띄운다. PC에서 바꾼 날짜가 갤럭시의 수동 새로고침 후 정확히 반영되는지 확인한다.
- [ ] 테스트 DB에 항목을 저장하고 Windows에서 확인한 뒤, 테스트 앱을 재설치하고 연결 정보를 다시 입력한다. 저장된 항목과 날짜, 완료 상태가 다시 나타나야 한다. 기기에만 남은 초안은 이 복구 범위에 포함되지 않으므로, 저장 전후 상태를 입력창에서 구분한다.
- [ ] 별도 사본이 필요한 경우에 대비해 DB를 CSV로 내보내고, 테스트용 DB에서 주요 항목과 날짜를 다시 읽을 수 있는지 확인한다. 실제 사용 전에는 다가오는 삼성 리마인더 항목 몇 개만 우선 옮겨 양쪽 목록을 대조한다.

예정 파일 `app/src/main/java/com/mycalender/sync/RefreshWorker.kt`, `app/src/main/java/com/mycalender/widget/ScheduleWidget.kt`, `README.md`

## 4. 사용 전 확인표

| 시나리오 | 통과 기준 |
| --- | --- |
| 홈 화면에서 일정을 추가하고 날짜를 바꾼다 | 노션 목록과 월간 달력에서 같은 항목이 새 날짜로 이동한다. |
| 홈 화면에서 완료한다 | 위젯의 미완료 목록에서 빠지고 PC에 완료 상태가 반영된다. |
| PC에서 날짜나 완료 상태를 바꾼다 | 갤럭시에서 수동 새로고침하면 위젯의 날짜 구간과 목록이 업데이트된다. |
| 오프라인 상태에서 열거나 저장한다 | 기존 목록은 읽을 수 있고, 저장되지 않은 변경은 완료로 표시되지 않는다. |
| 서버에 생성했지만 앱이 응답을 받지 못한다 | 같은 `기록 ID`로 기존 항목을 확인할 수 있고, 자동 재전송으로 중복을 만들지 않는다. |
| 날짜 미정, 종일, 밤 11시 59분 일정을 등록한다 | 날짜 미정은 별도 구간에 남고, 날짜가 있는 항목은 서울 시간 기준 올바른 날짜를 표시한다. |
| 앱을 다시 설치하고 동일 DB에 연결한다 | 노션까지 저장한 항목을 다시 불러온다. |

로컬 검증은 `./gradlew.bat testDebugUnitTest assembleDebug`로, 위젯 배치·화면 전환·복구 확인은 실제 갤럭시와 Windows에서 진행한다. 완료 결과와 실제 설치 폰의 Android 버전을 기록한다.

## 공식 문서

- [Notion API 버전](https://developers.notion.com/reference/versioning) · [데이터 소스 조회](https://developers.notion.com/reference/query-a-data-source) · [호출 제한](https://developers.notion.com/reference/request-limits)
- [Jetpack Glance 위젯 갱신](https://developer.android.com/develop/ui/compose/glance/glance-app-widget) · [WorkManager 주기 작업](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) · [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [노션 캘린더 보기](https://www.notion.com/help/calendars) · [노션 내보내기와 복구](https://www.notion.com/help/back-up-your-data) · [PowerToys Always On Top](https://learn.microsoft.com/ko-kr/windows/powertoys/always-on-top)
