// The door on event day (JIKU-205): GUESTS tickets scanned in RUSH_MINUTES by
// VALIDATORS staff, while GUESTS/20 people keep refreshing the public card page
// (about 100 views a minute, under the per-card limit of 120).
// Run against the acceptance environment, never production:
//
//   k6 run -e API_URL=https://api.recette.example/api/v1 \
//          -e ORGANIZER_EMAIL=... -e ORGANIZER_PASSWORD=... scripts/load/checkin-rush.js
//
// See docs/runbooks/load-test.md for the preparation (tier, rate limits).
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';

const API = __ENV.API_URL || 'http://localhost:8080/api/v1';
const GUESTS = Number(__ENV.GUESTS || 500);
const VALIDATORS = Number(__ENV.VALIDATORS || 10);
const RUSH_MINUTES = Number(__ENV.RUSH_MINUTES || 5);

const scanDuration = new Trend('scan_duration', true);

export const options = {
    setupTimeout: '15m',
    scenarios: {
        door: {
            executor: 'per-vu-iterations',
            exec: 'scan',
            vus: VALIDATORS,
            iterations: Math.ceil(GUESTS / VALIDATORS),
            maxDuration: `${RUSH_MINUTES + 2}m`,
        },
        cardPage: {
            executor: 'constant-vus',
            exec: 'openCard',
            vus: Math.max(1, Math.floor(GUESTS / 20)),
            duration: `${RUSH_MINUTES}m`,
        },
    },
    thresholds: {
        scan_duration: ['p(95)<500'],
        'http_req_failed{scenario:door}': ['rate<0.01'],
        'http_req_duration{scenario:cardPage}': ['p(95)<800'],
        'checks{scenario:door}': ['rate>0.99'],
    },
};

function json(response, what) {
    if (response.status < 200 || response.status >= 300) {
        fail(`${what}: HTTP ${response.status} ${response.body}`);
    }
    return response.body ? response.json() : null;
}

function post(path, body, token) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers.Authorization = `Bearer ${token}`;
    return http.post(`${API}${path}`, body === undefined ? null : JSON.stringify(body), { headers });
}

function get(path, token) {
    return http.get(`${API}${path}`, token ? { headers: { Authorization: `Bearer ${token}` } } : {});
}

export function setup() {
    const { accessToken: token } = json(
        post('/auth/login', { email: __ENV.ORGANIZER_EMAIL, password: __ENV.ORGANIZER_PASSWORD }),
        'login',
    );

    const start = new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString();
    const event = json(
        post(
            '/events',
            {
                name: `Load test ${new Date().toISOString()}`,
                startDateTime: start,
                timezone: 'Africa/Conakry',
                location: 'Conakry',
                invitationChannels: ['EMAIL'],
            },
            token,
        ),
        'create event',
    );
    json(post(`/events/${event.id}/publish`, undefined, token), 'publish');


    const open = json(
        http.put(`${API}/events/${event.id}/open-invitation`, JSON.stringify({ enabled: true, maxCompanions: 0 }), {
            headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        }),
        'open invitation',
    );

    // One "yes" per guest issues one ticket each, the same path a shared card takes.
    for (let i = 0; i < GUESTS; i += 1) {
        const phone = `+22462${String(i).padStart(7, '0')}`;
        json(post(`/open/${open.code}/responses`, { name: `Invité ${i}`, phone, answer: 'YES', companions: 0 }), `answer ${i}`);
    }

    const guests = json(get(`/events/${event.id}/guests`, token), 'guests');
    const codes = (Array.isArray(guests) ? guests : guests.content).map((g) => g.ticketCode).filter(Boolean);
    if (codes.length < GUESTS) fail(`only ${codes.length} tickets for ${GUESTS} guests`);

    const links = [];
    for (let v = 0; v < VALIDATORS; v += 1) {
        const validator = json(post(`/events/${event.id}/validators`, { label: `Porte ${v + 1}` }, token), 'validator');
        links.push(validator.link.split('/checkin/')[1]);
    }
    return { codes, links, cardCode: open.code };
}

export function scan(data) {
    // VU numbers are shared with the other scenario; the scenario's own
    // iteration counter gives each scan its own ticket.
    const index = exec.scenario.iterationInTest;
    const validator = index % VALIDATORS;
    if (index >= data.codes.length) return;
    const response = post(`/checkin/${data.links[validator]}/scan`, { ticketCode: data.codes[index] });
    scanDuration.add(response.timings.duration);
    check(response, {
        'scan accepted': (r) => r.status === 200 && r.json('outcome') === 'CHECKED_IN',
    });
    // Spread each validator's queue over the rush, like people walking up.
    sleep((RUSH_MINUTES * 60 * VALIDATORS) / data.codes.length);
}

export function openCard(data) {
    const response = get(`/open/${data.cardCode}`);
    check(response, { 'card page served': (r) => r.status === 200 });
    sleep(10 + Math.random() * 10);
}
