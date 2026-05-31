# Todongsan Local Infrastructure Guide

## 1. 목적

본 문서는 Todongsan 프로젝트의 로컬 개발 환경 구성을 설명한다.

모든 개발자는 동일한 Docker Compose 설정을 사용하여 로컬 MySQL 환경을 구축한다.

---

## 2. 사전 준비

필수 설치 항목

- Git
- Docker Desktop
- JDK 17
- IntelliJ IDEA

Docker Desktop이 정상 실행 중인지 확인한다.

```bash
docker --version
docker compose version
```

---

## 3. 프로젝트 구조

```text
todongsan
│
├── infra
│   ├── docker-compose.yml
│   └── mysql
│       └── init
│           ├── 00-create-databases.sql
│           ├── 01-market-schema.sql
│           ├── 02-memberpoint-schema.sql
│           ├── 03-battle-schema.sql
│           └── 04-insight-schema.sql
│
├── market-service
├── member-point-service
├── battle-service
└── insight-reputation-service
```

---

## 4. Docker MySQL 실행

infra 디렉토리로 이동한다.

```bash
cd infra
```

Docker Compose 실행

```bash
docker compose up -d
```

실행 확인

```bash
docker ps
```

정상 실행 시 다음 컨테이너가 표시된다.

```text
todongsan-mysql
```

---

## 5. Docker MySQL 종료

```bash
cd infra

docker compose down
```

---

## 6. Docker MySQL 완전 초기화

DB 데이터까지 삭제하고 처음부터 다시 생성한다.

```bash
cd infra

docker compose down -v

docker compose up -d
```

### 주의

`-v` 옵션은 MySQL 데이터 볼륨을 삭제한다.

기존 데이터가 모두 제거되므로 개발 초기 단계에서만 사용한다.

---

## 7. MySQL 접속

컨테이너 접속

```bash
docker exec -it todongsan-mysql bash
```

MySQL 접속

```bash
mysql -u root -p
```

비밀번호

```text
1234
```

---

## 8. 생성되는 Database

초기 실행 시 다음 Database가 자동 생성된다.

```text
memberpoint
battle
market
insight
```

확인

```sql
SHOW DATABASES;
```

---

## 9. SQL 초기화 스크립트

초기 SQL 파일 위치

```text
infra/mysql/init
```

예시

```text
00-create-databases.sql
01-market-schema.sql
02-memberpoint-schema.sql
03-battle-schema.sql
04-insight-schema.sql
```

### 주의

docker-entrypoint-initdb.d 스크립트는 MySQL 컨테이너 최초 생성 시에만 실행된다.

SQL 수정 후 재반영하려면

```bash
docker compose down -v
docker compose up -d
```

를 수행해야 한다.

---

## 10. Market Service 로컬 실행

### application-local.yml

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3307/market?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: root
    password: 1234
```

### 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

또는 IntelliJ Run Configuration

```text
SPRING_PROFILES_ACTIVE=local
```

---

## 11. AWS RDS 사용 정책

로컬 개발은 Docker MySQL을 사용한다.

AWS RDS는 다음 용도로만 사용한다.

- 통합 테스트
- 개발 서버 검증
- 배포 전 최종 검증

로컬 개발 시에는 Docker MySQL 사용을 권장한다.

---

## 12. 환경 변수 관리

다음 정보는 Git에 커밋하지 않는다.

```text
DB_HOST
DB_PORT
DB_USERNAME
DB_PASSWORD
```

권장 관리 방법

- .env
- GitHub Secret
- AWS Parameter Store

---

## 13. Troubleshooting

### 포트 충돌 확인

Windows

```bash
netstat -ano | findstr 3307
```

Mac / Linux

```bash
lsof -i :3307
```

### 컨테이너 상태 확인

```bash
docker ps
```

### 컨테이너 로그 확인

```bash
docker logs todongsan-mysql
```

### MySQL 접속 확인

```bash
docker exec -it todongsan-mysql mysql -u root -p
```

---

## 14. 개발 원칙

1. 모든 개발자는 동일한 Docker Compose를 사용한다.
2. Database 생성 및 변경은 SQL 스크립트로 관리한다.
3. 스키마 변경은 SQL 파일 수정 후 Pull Request로 공유한다.
4. 로컬 개발은 Docker MySQL을 사용한다.
5. AWS RDS는 통합 테스트 및 운영 검증 용도로 사용한다.
6. 환경 변수(.env)는 Git에 커밋하지 않는다.