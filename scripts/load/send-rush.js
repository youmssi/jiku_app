// An invitation batch during the event (JIKU-216): GUESTS invitations are sent
// by email while organizers keep their screens open and guests open the card
// (about 100 views a minute, under the per-card limit of 120).
// Checks that the batch does not slow the API down, and that every invitation
// is sent within SEND_MINUTES. Run against the acceptance environment, never
// production, with MAIL_TRANSPORT=log and MAIL_LOG_LATENCY=300ms on the API:
//
//   k6 run -e API_URL=https://api.recette.example/api/v1 \
//          -e ORGANIZER_EMAIL=... -e ORGANIZER_PASSWORD=... scripts/load/send-rush.js
//
// See docs/runbooks/load-test.md for the preparation.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const API = __ENV.API_URL || 'http://localhost:8080/api/v1';
const GUESTS = Number(__ENV.GUESTS || 1000);
const ORGANIZERS = Number(__ENV.ORGANIZERS || 20);
const SEND_MINUTES = Number(__ENV.SEND_MINUTES || 5);

const screenDuration = new Trend('organizer_screen_duration', true);
const sentWithinWindow = new Counter('invitations_sent');

export const options = {
    setupTimeout: '10m',
    scenarios: {
        send: { executor: 'shared-iterations', exec: 'send', vus: 1, iterations: 1, maxDuration: '1m' },
        organizers: {
            executor: 'constant-vus',
            exec: 'organizerScreens',
            vus: ORGANIZERS,
            duration: `${SEND_MINUTES}m`,
        },
        cardPage: { executor: 'constant-vus', exec: 'openCard', vus: 25, duration: `${SEND_MINUTES}m` },
        progress: {
            executor: 'shared-iterations',
            exec: 'waitForBatch',
            vus: 1,
            iterations: 1,
            startTime: '5s',
            maxDuration: `${SEND_MINUTES + 1}m`,
        },
    },
    thresholds: {
        organizer_screen_duration: ['p(95)<800'],
        'http_req_duration{scenario:cardPage}': ['p(95)<500'],
        'http_req_failed{scenario:organizers}': ['rate<0.01'],
        'http_req_failed{scenario:cardPage}': ['rate<0.01'],
        invitations_sent: [`count>=${GUESTS}`],
    },
};

function json(response, what) {
    if (response.status < 200 || response.status >= 300) {
        fail(`${what}: HTTP ${response.status} ${response.body}`);
    }
    return response.body ? response.json() : null;
}

function auth(token) {
    return { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } };
}

export function setup() {
    const { accessToken: token } = json(
        http.post(
            `${API}/auth/login`,
            JSON.stringify({ email: __ENV.ORGANIZER_EMAIL, password: __ENV.ORGANIZER_PASSWORD }),
            { headers: { 'Content-Type': 'application/json' } },
        ),
        'login',
    );

    const start = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
    const event = json(
        http.post(
            `${API}/events`,
            JSON.stringify({
                name: `Send rush ${new Date().toISOString()}`,
                startDateTime: start,
                timezone: 'Africa/Conakry',
                location: 'Conakry',
                invitationChannels: ['EMAIL'],
            }),
            auth(token),
        ),
        'create event',
    );
    json(http.post(`${API}/events/${event.id}/publish`, null, auth(token)), 'publish');

    let csv = 'firstName,lastName,email,phone\n';
    for (let i = 0; i < GUESTS; i += 1) csv += `Invité,${i},send-rush-${event.id}-${i}@load.test,\n`;
    json(
        http.post(
            `${API}/events/${event.id}/guests/import`,
            { file: http.file(csv, 'guests.csv', 'text/csv'), consentAttested: 'true' },
            { headers: { Authorization: `Bearer ${token}` } },
        ),
        'import',
    );

    const open = json(
        http.put(`${API}/events/${event.id}/open-invitation`, JSON.stringify({ enabled: true, maxCompanions: 0 }), auth(token)),
        'open invitation',
    );
    return { token, eventId: event.id, cardCode: open.code };
}

export function send(data) {
    const response = http.post(`${API}/events/${data.eventId}/invitations/send?channels=EMAIL`, null, auth(data.token));
    check(response, { 'batch accepted at once': (r) => r.status === 200 && r.timings.duration < 2000 });
}

export function organizerScreens(data) {
    for (const path of [`/events/${data.eventId}/guests`, `/events/${data.eventId}/invitations`, `/events/${data.eventId}`]) {
        const response = http.get(`${API}${path}`, auth(data.token));
        screenDuration.add(response.timings.duration);
        check(response, { 'organizer screen served': (r) => r.status === 200 });
    }
    sleep(3 + Math.random() * 2);
}

export function openCard(data) {
    const response = http.get(`${API}/open/${data.cardCode}`);
    check(response, { 'card page served': (r) => r.status === 200 });
    sleep(10 + Math.random() * 10);
}

export function waitForBatch(data) {
    const deadline = Date.now() + SEND_MINUTES * 60 * 1000;
    let sent = 0;
    while (Date.now() < deadline) {
        const statuses = http.get(`${API}/events/${data.eventId}/invitations`, auth(data.token)).json();
        sent = statuses.filter((s) => s.status === 'SENT' || s.status === 'DELIVERED').length;
        if (sent >= GUESTS) break;
        sleep(5);
    }
    sentWithinWindow.add(sent);
    console.log(`${sent}/${GUESTS} invitations sent within ${SEND_MINUTES} min`);
}
