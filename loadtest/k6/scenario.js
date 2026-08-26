// 부하테스트 k6 시나리오 (#911) — 앱 한계 탐색 (open model, arrival-rate)
//
// 대상은 dev 가 아니라 전용 환경(lt.api.piki.day)이다 — 앱·DB 를 따로 세운 prod 미러라
// 앱 박스에 mysql 동거가 없고, 부하가 dev 사용자에게 영향을 주지 않는다.
//
// 실행 (부하기 = 로컬 머신, repo 루트에서):
//   docker run --rm -i -v "$PWD/loadtest/k6":/scripts grafana/k6 run /scripts/scenario.js \
//     -e BASE_URL=https://lt.api.piki.day \
//     -e BROWSE_RATE_MAX=150 -e REGISTER_RATE=2 -e TOURNAMENT_RATE=1 -e GUEST_RATE=2 \
//     --summary-export /scripts/summary.json
//
// 전제:
//   * seed.sql 적재 완료 (lt 프리픽스 MEMBER 2,000명, 각 45 위시, 전부 READY)
//   * 로컬 공인 IP 가 nginx 레이트리밋 예외(geo)에 들어가 있다(배포 후 박스에서 sed + reload)
//   * extractor 는 부하 DB 박스에 동거하는 stub — 등록 부하가 실 extractor(prod 공유)로 새지 않는다
//   * 측정에 로컬 회선 왕복이 포함된다 — 절대 지연보다 램프에 따른 추세·꺾임 지점을 본다
//
// 시나리오 4개 (독립 arrival-rate, 총 10분):
//   browse     — 조회 mix (목록→상세→2페이지→me). 0 → BROWSE_RATE_MAX 로 계단 램프. 한계 탐색의 주 부하.
//   register   — 위시 URL 등록 (고유 URL). 파싱 큐 → stub 경로까지 태운다.
//   tournament — 생성→아이템 담기→start→매치 완주. 트랜잭션 쓰기 체인.
//   guest      — 게스트 가입 (users INSERT + 토큰 발급). 실서비스 유입 경로.
//
// 주의:
//   * 토큰 발급류 응답은 X-Client-Type: app 헤더가 없으면 body 토큰이 null (cookie 로만 감).
//   * URL 태그(name)를 고정해 k6 메트릭이 wishId 별로 쪼개지는 카디널리티 폭발을 막는다.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'https://lt.api.piki.day';
// 600 인 이유(등록 쿼터): 등록 한도는 두 축이고 둘 다 prod parity 로 켠 채 측정한다.
//   * 계정별(#339 → #935·#946 으로 통합·상향): 유저당 30건/1h. 초과 429 WISH-010.
//   * 전역 가용량(#927): 서비스 전체 3,000건/1h. 초과 503 SERVER_BUSY + Retry-After.
// round-robin 600명이면 계정별은 여유가 크고(아래 REGISTER_RATE 기준 유저당 2건), 지배 제약은
// 전역 상한 쪽이다. 러닝을 1시간 안에 반복하면 두 축 모두 잔량을 공유하므로 러닝 사이에
// 앱 박스 redis 의 quota:item:* 키를 지운다(런북) — user 키와 capacity 키가 함께 지워진다.
const TOKEN_USERS = parseInt(__ENV.TOKEN_USERS || '600', 10);
const BROWSE_RATE_MAX = parseInt(__ENV.BROWSE_RATE_MAX || '150', 10);
// 2 인 이유(전역 가용량 상한): 5/s x 10분 = 3,000 건으로 상한(#927, 3,000/1h)에 정확히 닿아
// 러닝 후반이 503 으로 오염된다. 2/s = 1,200 건이면 상한의 40% 라 여유가 남는다.
// 등록 레이트를 낮춰도 측정이 약해지지 않는다 — 한계 탐색의 주 부하는 browse 이고, 등록 경로의
// 병목은 레이트가 아니라 파싱 워커(maxPoolSize 8 · queueCapacity 0)라 이미 그쪽이 더 좁다.
// 상한 자체를 확인하고 싶으면 REGISTER_RATE 를 올려 503 이 뜨는 지점을 별도 러닝으로 잰다.
const REGISTER_RATE = parseInt(__ENV.REGISTER_RATE || '2', 10);
const TOURNAMENT_RATE = parseInt(__ENV.TOURNAMENT_RATE || '1', 10);
const GUEST_RATE = parseInt(__ENV.GUEST_RATE || '2', 10);

export const options = {
    setupTimeout: '300s',
    thresholds: {
        // 한계 탐색이 목적이라 중단 게이트가 아니라 관측 기준선이다 (abortOnFail 없음)
        http_req_failed: ['rate<0.02'],
        'http_req_duration{scenario:browse}': ['p(95)<1000'],
    },
    scenarios: {
        browse: {
            executor: 'ramping-arrival-rate',
            exec: 'browse',
            startRate: 10,
            timeUnit: '1s',
            preAllocatedVUs: 100,
            maxVUs: 600,
            stages: [
                { duration: '2m', target: Math.ceil(BROWSE_RATE_MAX * 0.3) },
                { duration: '3m', target: Math.ceil(BROWSE_RATE_MAX * 0.6) },
                { duration: '3m', target: BROWSE_RATE_MAX },
                { duration: '2m', target: BROWSE_RATE_MAX },
            ],
        },
        register: {
            executor: 'constant-arrival-rate',
            exec: 'register',
            rate: REGISTER_RATE,
            timeUnit: '1s',
            duration: '10m',
            preAllocatedVUs: 20,
            maxVUs: 60,
        },
        tournament: {
            executor: 'constant-arrival-rate',
            exec: 'tournament',
            rate: TOURNAMENT_RATE,
            timeUnit: '1s',
            duration: '10m',
            preAllocatedVUs: 10,
            maxVUs: 40,
        },
        guest: {
            executor: 'constant-arrival-rate',
            exec: 'guest',
            rate: GUEST_RATE,
            timeUnit: '1s',
            duration: '10m',
            preAllocatedVUs: 5,
            maxVUs: 20,
        },
    },
};

function pick(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function authParams(token, name, extraHeaders) {
    return {
        headers: Object.assign({ Authorization: `Bearer ${token}` }, extraHeaders || {}),
        tags: { name },
    };
}

// 시드 유저(lt 프리픽스)의 토큰을 미리 발급해 전 시나리오가 공유한다.
//
// 토큰 발급은 두 단계다. SecurityConfig 가 `/api/v1/dev/users` GET 만 permitAll 로 두고
// 나머지 `/api/v1/dev/**` 는 인증을 요구하므로(GUEST·MEMBER 모두 통과), 게스트 토큰을 한 번
// 받아 그것으로 시드 유저의 MEMBER 토큰을 발급한다. 게스트 토큰은 재사용한다.
export function setup() {
    const guestRes = http.post(`${BASE}/api/v1/auth/guest`, null, {
        headers: { 'X-Client-Type': 'app' },
        tags: { name: 'setup guest token' },
    });
    if (guestRes.status !== 200 && guestRes.status !== 201) {
        throw new Error(`guest token failed: ${guestRes.status}`);
    }
    const guestToken = guestRes.json('data.accessToken');
    if (!guestToken) throw new Error('guest token missing (X-Client-Type: app 없으면 쿠키로만 나간다)');

    const ids = [];
    let cursor = null;
    for (let page = 0; page < 20 && ids.length < TOKEN_USERS; page++) {
        const url = `${BASE}/api/v1/dev/users?size=200${cursor ? `&cursor=${cursor}` : ''}`;
        const r = http.get(url, { tags: { name: 'setup dev users list' } });
        if (r.status !== 200) throw new Error(`dev users list failed: ${r.status}`);
        // 응답 필드는 id 가 아니라 userId 다.
        for (const u of r.json('data') || []) {
            if (u.nickname && u.nickname.startsWith('lt')) ids.push(u.userId);
        }
        if (!r.json('pageResponse.hasNext')) break;
        cursor = r.json('pageResponse.nextCursor');
    }
    const users = [];
    for (const id of ids.slice(0, TOKEN_USERS)) {
        const r = http.post(`${BASE}/api/v1/dev/${id}/token`, null, {
            headers: { 'X-Client-Type': 'app', Authorization: `Bearer ${guestToken}` },
            tags: { name: 'setup token issue' },
        });
        if (r.status !== 200 && r.status !== 201) continue;
        const token = r.json('data.accessToken');
        if (token) users.push({ id, token });
    }
    if (users.length < 10) throw new Error(`seed user tokens too few: ${users.length} (seed.sql 적재됐는지 확인)`);
    console.log(`setup: ${users.length} seed user tokens ready`);
    return { users };
}

export function browse(data) {
    const u = pick(data.users);
    const list = http.get(`${BASE}/api/v1/wishlists?size=20`, authParams(u.token, 'GET /wishlists'));
    check(list, { 'wishlist 200': (r) => r.status === 200 });
    if (list.status !== 200) return;

    const rows = list.json('data') || [];
    if (rows.length > 0) {
        const wishId = pick(rows).wish.id;
        const detail = http.get(`${BASE}/api/v1/wishlists/${wishId}`, authParams(u.token, 'GET /wishlists/{id}'));
        check(detail, { 'wish detail 200': (r) => r.status === 200 });
    }
    const next = list.json('pageResponse.nextCursor');
    if (next && Math.random() < 0.3) {
        http.get(`${BASE}/api/v1/wishlists?size=20&cursor=${next}`, authParams(u.token, 'GET /wishlists p2'));
    }
    if (Math.random() < 0.5) {
        http.get(`${BASE}/api/v1/users/me`, authParams(u.token, 'GET /users/me'));
    }
}

export function register(data) {
    // 랜덤이 아니라 round-robin — 등록 쿼터(유저당 10건/1h)에 확률적으로 걸리는 꼬리를 없앤다.
    const u = data.users[exec.scenario.iterationInTest % data.users.length];
    // 고유 URL — item_links url_hash 유니크에 안 걸려 매번 새 item + PENDING snapshot 생성.
    // 도메인은 정책 DB 에 없는(=허용) 팀 소유 서브도메인. 파싱은 stub 이 받는다.
    const url = `https://loadtest.piki.day/products/reg-${__VU}-${__ITER}-${Date.now()}`;
    const r = http.post(`${BASE}/api/v1/wishlists`, JSON.stringify({ url }),
        authParams(u.token, 'POST /wishlists', { 'Content-Type': 'application/json' }));
    check(r, { 'register 201': (res) => res.status === 201 });
}

export function tournament(data) {
    const u = pick(data.users);
    const p = (name, extra) => authParams(u.token, name, extra);
    const json = { 'Content-Type': 'application/json' };

    const list = http.get(`${BASE}/api/v1/wishlists?size=20`, p('GET /wishlists (tour)'));
    if (list.status !== 200) return;
    const ready = (list.json('data') || []).filter(
        (w) => w.item && w.item.status === 'READY' && w.item.price !== null,
    );
    const itemIds = [...new Set(ready.map((w) => w.item.id))].slice(0, 4);
    if (itemIds.length < 2) return;

    const created = http.post(`${BASE}/api/v1/tournaments`,
        JSON.stringify({ name: `lt-${__VU}-${__ITER}`.slice(0, 30) }), p('POST /tournaments', json));
    check(created, { 'tournament 201': (r) => r.status === 201 });
    if (created.status !== 201) return;
    const tid = created.json('data.tournamentId');

    const added = http.post(`${BASE}/api/v1/tournaments/${tid}/items/wish`,
        JSON.stringify({ itemIds }), p('POST /tournaments/{id}/items/wish', json));
    if (added.status !== 200) return;

    const started = http.post(`${BASE}/api/v1/tournaments/${tid}/start`, null,
        p('POST /tournaments/{id}/start'));
    check(started, { 'start 200': (r) => r.status === 200 });
    if (started.status !== 200) return;

    // 매치 완주 — 페어는 서버 브래킷이 정하므로 detail 의 currentMatch 를 그대로 쓴다.
    for (let i = 0; i < 40; i++) {
        const d = http.get(`${BASE}/api/v1/tournaments/${tid}`, p('GET /tournaments/{id}'));
        if (d.status !== 200) return;
        const prog = d.json('data.inProgress');
        if (!prog || !prog.currentMatch) break; // 완료
        const first = prog.currentMatch.first.tournamentItemId;
        const second = prog.currentMatch.second.tournamentItemId;
        const m = http.post(`${BASE}/api/v1/tournaments/${tid}/matches`, JSON.stringify({
            currentRound: prog.currentRound,
            firstTournamentItemId: first,
            secondTournamentItemId: second,
            selectedTournamentItemId: Math.random() < 0.5 ? first : second,
        }), p('POST /tournaments/{id}/matches', json));
        check(m, { 'match 200': (r) => r.status === 200 });
        if (m.status !== 200) return;
        if (m.json('data.completed')) break;
    }
}

export function guest() {
    // 게스트 가입: users INSERT + 토큰 발급. 닉네임 풀 소진은 숫자 suffix 확장(#921)으로
    // 해소돼 누적 상한이 없다 — rate 상향 여지 있음(기본 2/s 는 실유입 비율 반영).
    const r = http.post(`${BASE}/api/v1/auth/guest`, null, {
        headers: { 'X-Client-Type': 'app' },
        tags: { name: 'POST /auth/guest' },
    });
    check(r, { 'guest 201': (res) => res.status === 201 });
    if (r.status !== 201) return;
    const token = r.json('data.accessToken');
    if (token) {
        http.get(`${BASE}/api/v1/users/me`, authParams(token, 'GET /users/me (guest)'));
    }
}
