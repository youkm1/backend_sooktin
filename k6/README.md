# 커리어카드 검색 성능 측정 런북

검색 지연시간에서 **캐시가 기여한 몫**과 **쿼리 최적화(N+1 수정)가 기여한 몫**을
분리해서 측정하기 위한 절차. 캐시 on/off × N+1 수정 전/후의 2×2 조합을 재고,
Redis 캐시 히트율은 실측값으로 확보한다.

## 인증

`/career-cards/**` 는 `authenticated()` 라 토큰이 필요하지만, `/auth/**` 는 `permitAll` 이고
`JwtAuthenticationFilter.shouldNotFilter()` 에서도 제외되어 있다.
그래서 k6 가 **`setup()` 에서 한 번 로그인해 accessToken 을 받고**, 이후 모든 검색 요청에
`Authorization: Bearer <token>` 를 붙인다. 별도 준비는 필요 없다.

시딩 계정은 `isEnabled()` 등이 전부 기본값 `true` 라 이메일 인증 없이 바로 로그인된다.
토큰 만료는 1시간(`jwt.access-expiration`)이므로 그보다 짧은 부하 테스트에서는 갱신이 필요 없다.

## 0. 사전 준비

```bash
docker compose -f docker-compose.local.yml up -d mysql redis
```

시딩(최초 1회만). 5,000건 생성에 수십 초 걸린다. SQL 로깅을 끄고 돌려야 빠르다.

```bash
./gradlew bootRun --args='
  --spring.profiles.active=local
  --sooktin.seed.enabled=true
  --sooktin.seed.count=5000
  --spring.jpa.show-sql=false
  --logging.level.org.hibernate.SQL=WARN'
```

로그에 `[seed] 커리어카드 5000건 생성 완료` 가 뜨면 종료한다.
시딩된 계정은 전부 비밀번호가 `seedPassw0rd!` 이고, k6 는 `seed00000@sookmyung.ac.kr` 로 로그인한다.

> ⚠️ **측정 중에는 반드시 `--spring.jpa.show-sql=false --logging.level.org.hibernate.SQL=WARN` 로 띄울 것.**
> SQL 로깅이 켜져 있으면 로그 I/O 가 응답시간을 지배해서 측정값이 전부 무의미해진다.

## 1. 4가지 조합 측정

매 측정 **전에** 캐시를 비운다. 안 그러면 이전 실행의 캐시가 남아 히트율이 부풀려진다.

```bash
redis-cli FLUSHDB
```

앱 기동 (조합에 따라 `spring.cache.type` 만 바꾼다):

```bash
# 캐시 OFF
./gradlew bootRun --args='--spring.profiles.active=local --spring.cache.type=none
  --spring.jpa.show-sql=false --logging.level.org.hibernate.SQL=WARN
  --logging.level.com.sooktin=WARN'

# 캐시 ON
./gradlew bootRun --args='--spring.profiles.active=local --spring.cache.type=redis
  --spring.jpa.show-sql=false --logging.level.org.hibernate.SQL=WARN
  --logging.level.com.sooktin=WARN'
```

> `--logging.level.com.sooktin=WARN` 도 반드시 넣을 것.
> `JwtAuthenticationFilter.shouldNotFilter()` 가 **요청마다** `log.info` 를 찍는데,
> local 프로필 기본값이 `com.sooktin: DEBUG` 라 그대로 두면 로그가 측정값을 왜곡한다.

부하:

```bash
k6 run k6/search-load.js
k6 run -e VUS=30 -e DURATION=90s k6/search-load.js   # 부하를 올리고 싶을 때
```

첫 실행은 JIT 워밍업 때문에 느리게 나온다. **한 번 버리고 두 번째 결과를 쓴다.**

### 측정 결과

커리어카드 5,000건, 20 VU / 60초, 로컬 환경(MySQL 8.0 + Redis 7, 둘 다 도커) 기준.

| 조건 | 처리량 | p95 | 중앙값 | 실패율 | 히트율 |
|---|---|---|---|---|---|
| ① 상관 서브쿼리 · 캐시 OFF | 0.67 req/s | 60s (타임아웃) | 20.15s | 97.56% | - |
| ② 조인으로 수정 · 캐시 OFF | 23.7 req/s | 1.03s | 715ms | 0% | - |
| ③ 조인으로 수정 · 캐시 ON | 154.6 req/s | 82ms | 6.8ms | 0% | 96.9% |

- **①→②** 쿼리 구조 수정 기여분: 처리량 35배, 실패율 97.56% → 0%
- **②→③** 캐시 기여분: 처리량 6.5배, 중앙값 715ms → 6.8ms
- 히트율 96.9% = hit 9,122 / (hit 9,122 + miss 291)

①에서는 검색이 커넥션을 오래 점유해 HikariCP 풀(10)이 고갈됐고,
그 여파로 `/auth/login` 까지 connection-timeout 20초 후 500을 반환했다.
원인은 `cc.experiences.any()` 가 카드 한 건마다 careercards 를 다시 스캔하는
상관 서브쿼리로 번역된 것이었다 (`SHOW FULL PROCESSLIST` 로 확인).

③의 응답 분포가 `min=2.5ms / med=6.8ms / p95=82ms / max=1.69s` 로 이봉형인데,
앞쪽이 캐시 히트, 뒤쪽이 미스로 DB를 타는 요청이다.
즉 캐시는 아직 남아 있는 느린 쿼리(인덱스를 못 타는 `LIKE '%kw%'`,
EAGER `@ElementCollection` 3개로 인한 N+1)를 가려주고 있는 상태다.

**히트율은 키워드 분포에 전적으로 의존한다.** 위 96.9% 는 `search-load.js` 의
hot 20% / warm 50% / cold 30% 분포에서 나온 값이므로, 인용할 때 분포를 함께 밝혀야 한다.

## 2. 지표 읽는 법

### p95 지연시간
k6 출력의 `search_latency` 행에서 `p(95)` 를 본다. (`http_req_duration` 이 아니라 이쪽을 쓴다 — 로그인 요청이 섞이지 않는다.)

### 캐시 히트율

```bash
curl -s localhost:8080/actuator/prometheus | grep 'cache_gets_total.*careerCardSearch'
```

```
hit  = cache_gets_total{cache="careerCardSearch",result="hit"}
miss = cache_gets_total{cache="careerCardSearch",result="miss"}

히트율 = hit / (hit + miss)
```

한 줄로:

```bash
curl -s localhost:8080/actuator/prometheus \
  | awk -F'[ }]' '/cache_gets_total.*careerCardSearch.*result="hit"/{h=$NF}
                  /cache_gets_total.*careerCardSearch.*result="miss"/{m=$NF}
                  END{if(h+m>0) printf "hit rate: %.1f%% (hit=%d miss=%d)\n", 100*h/(h+m), h, m}'
```

> 이 수치가 이력서에 쓸 히트율의 **유일하게 정당한 출처**다.
> `CacheConfig` 의 `enableStatistics()` 가 있어야 이 메트릭이 나온다.

### 쿼리 수 (N+1 확인용)

```bash
curl -s localhost:8080/actuator/prometheus | grep hibernate_statements_total
```

부하 **전후로 각각 찍어서 차이**를 본다. 요청 수로 나누면 요청당 쿼리 수가 나온다.

```
요청당 쿼리 수 = (부하 후 값 − 부하 전 값) / k6 의 http_reqs 수
```

N+1 이 살아 있으면 카드 10장 페이지 기준 **30개 이상**, 수정 후에는 **4개 내외**가 나와야 한다.

## 3. N+1 수정 (③④ 측정 전에 적용)

`domain/CareerCard.java` 의 `@ElementCollection` 3개를 지연 로딩 + 배치로 바꾼다.

```java
@ElementCollection(fetch = FetchType.LAZY)
@BatchSize(size = 100)   // org.hibernate.annotations.BatchSize
```

대상: `imageUrls`, `experiences`, `skills`

지연 로딩으로 바꿔도 OSIV(`spring.jpa.open-in-view`, 기본 true)가 켜져 있어
웹 요청 안에서는 `LazyInitializationException` 이 나지 않는다.
`@BatchSize` 가 붙으면 카드마다 1건씩 조회하는 대신 `IN` 절로 최대 100건씩 묶어 가져온다.

## 4. 해석할 때 주의할 점

- **빈 결과는 캐시되지 않는다.** `@Cacheable(unless = "#result.careerCards.isEmpty()")` 때문이다.
  k6 의 `search_empty_results` 비율이 높으면 히트율이 그만큼 낮게 나오므로 같이 보고해야 한다.
- **검색 캐시 TTL 은 10분**(`CacheConfig.SEARCH_TTL`)이다. 10분 넘는 부하 테스트는 중간에 만료가 섞인다.
- 히트율은 **키워드 분포에 전적으로 의존한다.** `search-load.js` 는 hot 20% / warm 50% / cold 30% 로 잡아뒀다.
  이 분포를 바꾸면 히트율도 바뀌므로, 수치를 인용할 때 **분포를 함께 말해야** 정직한 숫자가 된다.
- 로컬 MySQL/Redis 는 프로덕션(RDS/ElastiCache)과 네트워크 지연이 다르다.
  측정값을 인용할 때 **"로컬 환경 기준"**임을 밝힐 것.
