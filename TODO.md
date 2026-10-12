# TODO

## 원칙: 성능 우선

- **기본값은 지금 버전(가장 빠른 경로)**. 아래 통제 기능은 모두 opt-in, 기본 꺼짐.
- 꺼져 있을 때 relay hot path(epoll worker, UDP worker)에 추가 비용이 없어야 한다
  (atomic 연산·락·조회 추가 금지, 분기 하나 정도까지만 허용).
- 켜졌을 때도 바이트 집계는 패킷마다 공유 atomic을 건드리지 말고,
  conn 단위 카운터(소유 worker만 씀) → 주기적 스캔/finalize 시 합산 방식으로.

## 1. 접속 기기별 통제 (opt-in "통제 모드")

### 제약
- Android 10+ 앱은 ARP/neighbor 테이블을 못 읽는다 → 클라이언트 **IP ↔ MAC/기기 이름 매핑 불가**.
  P2P clientList(이름·MAC)와 프록시가 보는 IP를 연결할 수 없다.
- 그래서 기기 식별은 (a) 클라이언트 IP, (b) 기기별 프록시 계정 두 가지뿐.

### 범위
- [ ] **IP별 접속 현황**: 현재 연결 수(TCP/UDP), 업·다운로드 바이트, 마지막 활동 시각
- [ ] **IP 차단/해제**: 차단 시 신규 연결 거부 + 기존 연결 종료
- [ ] **기기별 계정**: 이름/아이디/비밀번호/사용 여부. 계정이 하나라도 있으면 무인증 거부.
      SOCKS5(RFC1929)와 HTTP(Proxy-Authorization: Basic) 둘 다 같은 계정 테이블 사용.
      기존 단일 계정 설정과의 호환(계정 목록이 비어 있으면 기존 동작).
- [ ] **계정별 데이터 한도**(일/누적): 초과 시 계정 비활성화 + 연결 종료
- [ ] 나중: 시간대 제한, 사이트(도메인) 차단

### 구현 메모
- native: `full_conn_t`/`udp_conn_t`에 client IP·account id·바이트 카운터 추가,
  인증 성공 시 account id 기록, account/IP 단위로 연결을 닫는 API(JNI).
- Kotlin: 통제 모드일 때만 몇 초 주기로 native 통계를 읽어 UI 갱신·한도 판정.
- 영속화: SharedPreferences(JSON) 또는 작은 Room DB 없이 JSON 파일.

## 2. 접속 정보 QR (성능 영향 없음 — 통제 모드와 무관하게 항상 사용 가능)

- [ ] Wi-Fi 접속 QR: `WIFI:T:WPA;S:<ssid>;P:<pass>;;` (폰 카메라로 찍으면 바로 접속)
- [ ] 프록시 정보 QR: `socks5://[user:pass@]192.168.49.1:1080` (+ HTTP 프록시 동일 포트 안내)
- [ ] QR 생성: zxing `core`(Apache-2.0, 순수 Java) 의존성 추가 또는 경량 인코더

## 3. 기타

- [ ] 접속 기기 IP 표시 방법 재조사(Android 버전별로 가능한 공식 API가 있는지)
- [ ] 무선 환경에서 Wi-Fi 절전 해제 잠금 효과 실측(화면 꺼짐 장시간 사용)
- [ ] 크래시 경로의 남은 그룹 정리 실기기 검증
