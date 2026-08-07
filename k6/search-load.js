// 커리어카드 검색 API 부하 테스트.
//
// 목적: N+1 수정 전/후, 캐시 on/off 조합으로 검색 지연시간을 재고
//       Redis 캐시 히트율을 실제 수치로 확보한다.
//
// 실행:
//   k6 run k6/search-load.js
//   k6 run -e BASE_URL=http://localhost:8080 -e VUS=20 -e DURATION=60s k6/search-load.js
//
// 사전 조건: sooktin.seed.enabled=true 로 시딩이 끝나 있어야 한다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SEED_EMAIL = __ENV.SEED_EMAIL || 'seed00000@sookmyung.ac.kr';
const SEED_PASSWORD = __ENV.SEED_PASSWORD || 'seedPassw0rd!';

const searchLatency = new Trend('search_latency', true);
const emptyResults = new Rate('search_empty_results');

export const options = {
  scenarios: {
    search: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '60s',
    },
  },
  thresholds: {
    // 이력서에 쓸 수치는 p95 기준으로 잡는다
    'search_latency': ['p(95)<2000'],
    'http_req_failed': ['rate<0.01'],
  },
};

// 캐시 히트율이 의미 있는 값으로 나오도록 키워드 분포를 3층으로 나눈다.
// 전부 같은 키워드면 히트율 99%, 전부 랜덤이면 0% 라 어느 쪽도 현실적이지 않다.
const HOT = ['백엔드', '컴퓨터과학', 'Java', '네이버', '데이터사이언스'];
const WARM = [
  '프론트엔드', '인프라', 'PM', 'QA', '디자이너', '데이터엔지니어',
  'Spring', 'Python', 'AWS', 'Docker', 'React', 'MySQL', 'Redis', 'Kafka',
  '카카오', '토스', '쿠팡', '라인', '당근마켓', '삼성전자',
  '경영학', '통계학', '미디어학', '법학', '소프트웨어학',
  'IT부서', '마케팅팀', '디자인팀', '연구소', '전략기획팀',
];
// 콜드: 매칭은 되지만 조합이 다양해 캐시 키가 매번 달라지는 다중 키워드 질의
const COLD_LEFT = ['Java', 'Python', 'AWS', 'React', 'Redis', 'Kafka', 'Docker', 'Go'];
const COLD_RIGHT = ['백엔드', '인프라', 'PM', '네이버', '카카오', '토스', '경영학'];

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

// 20% hot / 50% warm / 30% cold
function nextKeyword() {
  const r = Math.random();
  if (r < 0.2) return pick(HOT);
  if (r < 0.7) return pick(WARM);
  return `${pick(COLD_LEFT)} ${pick(COLD_RIGHT)}`;
}

export function setup() {
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ email: SEED_EMAIL, password: SEED_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  if (res.status !== 200) {
    throw new Error(
      `로그인 실패 (${res.status}). 시딩이 됐는지, SEED_EMAIL/SEED_PASSWORD 가 맞는지 확인하세요. body=${res.body}`,
    );
  }

  const token = res.json('data.accessToken');
  if (!token) {
    throw new Error(`accessToken 을 찾지 못했습니다. body=${res.body}`);
  }
  return { token };
}

export default function (data) {
  const keyword = nextKeyword();
  // 같은 키워드라도 페이지가 다르면 캐시 키가 달라진다 (검색 캐시 키에 page/size 포함)
  const page = Math.random() < 0.8 ? 0 : Math.floor(Math.random() * 3);

  const res = http.get(
    `${BASE_URL}/career-cards/search?keyword=${encodeURIComponent(keyword)}&page=${page}&size=10`,
    {
      headers: { Authorization: `Bearer ${data.token}` },
      tags: { name: 'search' },
    },
  );

  searchLatency.add(res.timings.duration);

  const ok = check(res, {
    'status 200': (r) => r.status === 200,
  });

  if (ok) {
    // 빈 결과는 unless 조건 때문에 캐시에 저장되지 않는다.
    // 이 비율이 높으면 히트율이 낮게 나오므로 같이 봐야 한다.
    const total = res.json('data.totalCount');
    emptyResults.add(total === 0);
  }

  sleep(0.1);
}
