/**
 * Arranges test data through the real REST API.
 *
 * Setup goes through the API and only the flow under test is driven through the
 * UI. Clicking through event creation and guest import before every guest or
 * validator journey would make the suite slow and brittle, and would report a
 * failure in the setup as a failure of the thing being tested.
 */

import type { Organizer } from './organizer';

const API_URL = process.env.E2E_API_URL ?? 'http://localhost:8080/api/v1';

async function call<T>(
    path: string,
    init: RequestInit & { token?: string } = {},
): Promise<T> {
    const { token, headers, ...rest } = init;
    const response = await fetch(`${API_URL}${path}`, {
        ...rest,
        headers: {
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
            ...headers,
        },
    });
    if (!response.ok) {
        throw new Error(`${init.method ?? 'GET'} ${path} → ${response.status}: ${await response.text()}`);
    }
    return response.status === 204 ? (null as T) : ((await response.json()) as T);
}

/** A token bound to the organizer's organization, which the tenant filter needs. */
export async function login(organizer: Organizer): Promise<string> {
    const { accessToken } = await call<{ accessToken: string }>('/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: organizer.email, password: organizer.password }),
    });
    return accessToken;
}

export async function createEvent(token: string, name: string): Promise<string> {
    const start = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();
    const event = await call<{ id: string }>('/events', {
        method: 'POST',
        token,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            name,
            description: 'Created by the end-to-end suite',
            startDateTime: start,
            endDateTime: new Date(Date.parse(start) + 4 * 60 * 60 * 1000).toISOString(),
            timezone: 'Africa/Conakry',
            location: 'Conakry',
            maxCapacity: 50,
            invitationChannels: ['EMAIL'],
        }),
    });
    return event.id;
}

export async function publishEvent(token: string, eventId: string): Promise<void> {
    await call(`/events/${eventId}/publish`, { method: 'POST', token });
}

export interface ImportResult {
    imported?: number;
    total?: number;
    [key: string]: unknown;
}

/** Imports guests from an in-memory CSV, exactly as the organizer's upload does. */
export async function importGuests(
    token: string,
    eventId: string,
    rows: { firstName: string; lastName: string; email: string }[],
): Promise<ImportResult> {
    // Header must match docs/guest-import-template.csv, which is the file real
    // organizers download and fill in.
    const csv = ['firstName,lastName,email,phone', ...rows.map((r) => `${r.firstName},${r.lastName},${r.email},`)].join(
        '\n',
    );

    const form = new FormData();
    form.append('file', new Blob([csv], { type: 'text/csv' }), 'guests.csv');

    return call<ImportResult>(`/events/${eventId}/guests/import`, { method: 'POST', token, body: form });
}

export async function sendInvitations(token: string, eventId: string): Promise<unknown> {
    return call(`/events/${eventId}/invitations/send`, { method: 'POST', token });
}

export interface GuestSummary {
    id: string;
    email: string;
    rsvpStatus?: string;
    [key: string]: unknown;
}

export async function listGuests(token: string, eventId: string): Promise<GuestSummary[]> {
    const body = await call<GuestSummary[] | { content: GuestSummary[] }>(`/events/${eventId}/guests`, { token });
    return Array.isArray(body) ? body : body.content;
}

/** Creates a validator link and returns the shareable check-in URL. */
export async function createValidator(token: string, eventId: string, label: string): Promise<string> {
    const validator = await call<{ url?: string; link?: string; token?: string }>(`/events/${eventId}/validators`, {
        method: 'POST',
        token,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ label }),
    });
    const url = validator.url ?? validator.link ?? (validator.token ? `/checkin/${validator.token}` : undefined);
    if (!url) {
        throw new Error(`Validator response carried no link: ${JSON.stringify(validator)}`);
    }
    return url;
}
