# My-Calender

갤럭시 홈 화면에서 과제와 일정을 추가하고, 날짜를 수정하고, 완료를 체크하는 개인용 Android 앱입니다. 일정은 노션의 같은 데이터베이스에 저장되므로 Windows에서도 열 수 있습니다. 최초 연결과 갱신에는 인터넷 연결이 필요합니다.

## APK 설치와 위젯 추가

1. 패키지의 `My-Calender-debug.apk`를 갤럭시로 옮겨 설치합니다. 직접 빌드한 경우 APK는 `build/` 폴더에 있습니다. 지원 버전은 Android 8.0(API 26) 이상입니다.
2. 앱을 열고 아래의 `데이터 소스 ID`와 `내부 연결 토큰`을 입력합니다.
3. 갤럭시 홈 화면의 빈 곳을 길게 눌러 **위젯 → My-Calender**를 추가합니다. `+`는 새 일정, 일정 제목은 수정, 동그라미는 완료, `↻`는 노션 새로고침입니다.
4. 중요한 기록을 옮기기 전에 테스트 일정 하나를 추가하고 Windows의 노션에서 제목과 날짜가 맞는지 확인합니다. 날짜를 변경한 뒤 갤럭시 위젯의 `↻`를 눌러 변경을 가져옵니다.

## 노션 준비

먼저 테스트용으로 **일정 테스트** 데이터베이스를 만들고 다음 속성을 추가합니다. 속성 이름의 띄어쓰기를 포함해 표와 동일하게 맞춥니다.

| 속성 이름 | 유형 | 설정 |
| --- | --- | --- |
| `이름` | 제목 | 기본 제목 열의 이름을 바꿉니다. |
| `날짜` | 날짜 | 종일이나 날짜 없음도 사용할 수 있습니다. |
| `상태` | 상태 | `할 일`과 `완료`를 추가합니다. `완료`는 완료 그룹에 둡니다. |
| `중요` | 체크박스 | 체크하면 위젯에 별표를 표시합니다. |
| `기록 ID` | 텍스트 | 앱이 중복 확인에 사용합니다. 직접 입력할 필요는 없습니다. |

1. 노션의 **내 연결(My integrations)**에서 개인 워크스페이스용 내부 연결을 생성하고, 콘텐츠 읽기·삽입·업데이트 권한을 지정합니다. [노션 내부 연결 만들기](https://www.notion.com/help/create-integrations-with-the-notion-api)
2. **일정 테스트** DB에서 `··· → 연결`을 열어 방금 만든 내부 연결을 추가합니다. 테스트가 끝나면 같은 방식으로 실제 `일정` DB를 연결합니다.
3. 데이터베이스 메뉴의 **데이터 소스 관리(Manage data sources)**에서 해당 소스의 ID를 복사합니다. 개발자 모드의 개발자 바에서도 **Data source ID**를 확인할 수 있습니다. [데이터 소스](https://www.notion.com/help/data-sources)
4. My-Calender 앱에 데이터 소스 ID와 내부 연결 토큰을 입력합니다. 토큰은 휴대폰에만 입력하고 저장소나 채팅에 복사하지 않습니다.

Windows의 노션에서 `예정` 보기에는 `상태 ≠ 완료` 필터와 날짜순 정렬을 설정합니다. 같은 DB에 `날짜` 기준 `월간` 캘린더 보기를 추가합니다. 작은 노션 창을 화면 위에 고정하려면 PowerToys에서 **Always On Top**을 켠 뒤 `Win + Ctrl + T`를 누릅니다. [노션 캘린더 보기](https://www.notion.com/help/calendars) · [PowerToys Always On Top](https://learn.microsoft.com/ko-kr/windows/powertoys/always-on-top)

## 저장과 복구

- 마지막으로 불러온 일정은 갤럭시에서 오프라인으로 볼 수 있습니다. PC에서 수정한 데이터는 위젯의 `↻`로 가져올 수 있고, 앱은 30분 간격의 백그라운드 갱신도 예약합니다. 실행 시각은 Android의 절전 상태에 따라 달라집니다.
- 새 일정을 저장하다 연결이 끊겼다면 앱의 **저장 확인이 필요한 항목**에서 다시 확인할 수 있습니다. 노션에서 항목을 찾으면 기존 항목을 사용합니다. 재전송 안내가 표시될 때는 노션에 항목이 없는지 확인한 다음 진행합니다.
- 노션에서 시작일과 종료일을 지정한 일정은 앱에서 기간을 확인할 수 있습니다. 기간 수정은 노션에서 합니다.
- 앱을 재설치한 뒤 같은 DB에 다시 연결하면 노션에 저장된 기록을 읽을 수 있습니다. 완료 항목은 노션에서 확인합니다. 휴대폰에만 남아 있던 초안은 앱을 삭제하면 복구할 수 없습니다.
- 별도 사본은 노션 DB를 CSV로 내보내 보관합니다. 노션 보기 설정은 필요할 때 다시 구성합니다. [노션 내보내기와 복구](https://www.notion.com/help/back-up-your-data)

## Windows에서 빌드

Android Studio의 JDK와 번들 Kotlin 컴파일러, Android SDK Platform 35 이상, Android SDK Build-Tools 36 이상을 사용합니다. 빌드는 설치된 도구만 이용하며 별도 Gradle 의존성을 내려받지 않습니다.

첫 구현은 홈 위젯에 `RemoteViews`, 로컬 저장에 `SQLiteOpenHelper`, 주기 갱신에 `JobScheduler`를 사용합니다. 앱 화면과 위젯은 Kotlin으로 작성했습니다.

```powershell
& .\scripts\build.ps1
```

별도 위치에 설치했다면 경로를 지정합니다.

```powershell
& .\scripts\build.ps1 -StudioHome 'C:\Program Files\Android\Android Studio' -SdkHome 'C:\Users\YourName\AppData\Local\Android\Sdk'
& .\scripts\build.ps1 -TestsOnly
```

빌드는 날짜 구간, 캐시 보존, 중복 저장 방지, 완료 실패 검사를 실행한 뒤 `build/My-Calender-debug.apk`를 생성하고 서명을 검증합니다. Android 디버그 서명 키는 `build/`에 생깁니다. 기존 설치에 덮어쓰려면 같은 키가 필요하므로, 내부 테스트용 업데이트를 이어가는 동안 이 키를 보존합니다.
