import { expect, test } from '@playwright/test';

import { login } from '../fixtures/api';
import { onboardedOrganizer } from '../fixtures/organizer';

/**
 * Day-line console (JIKU-88): a professional serves, on the same day and from the
 * same single list, a booked appointment and a walk-in client.
 *
 * The scenario books the appointment and prepares the service through the API
 * (the flow under test is the console, not booking), then drives the console in
 * the UI: confirmation of the requested rendez-vous, its arrival, walk-in at the
 * counter, SUIVANT calling the longest wait first, take-in-charge and finish for
 * both.
 */

interface DayService {
    serviceId: string;
    name: string;
}

interface ApiOptions {
    token?: string;
    body?: unknown;
    method?: string;
}

async function api<T>(path: string, { token, body, method = 'GET' }: ApiOptions = {}): Promise<T> {
    const response = await fetch(`${process.env.E2E_API_URL ?? 'http://localhost:8080/api/v1'}${path}`, {
        method,
        headers: {
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
            ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
    });
    if (!response.ok) {
        throw new Error(`${method} ${path} → ${response.status}: ${await response.text()}`);
    }
    return response.status === 204 ? (null as T) : ((await response.json()) as T);
}

/** Creates a service open all week on Africa/Conakry, returns it bookable. */
async function createDayService(token: string): Promise<DayService> {
    const service = await api<{ id: string }>('/services', {
        token,
        method: 'POST',
        body: { name: 'Coupe', timezone: 'Africa/Conakry' },
    });
    const resource = await api<{ id: string }>('/resources', {
        token,
        method: 'POST',
        body: { name: 'Coiffeuse', type: 'PERSON', timezone: 'Africa/Conakry' },
    });
    for (let day = 1; day <= 7; day++) {
        await api(`/resources/${resource.id}/availability`, {
            token,
            method: 'POST',
            body: { dayOfWeek: day, start: '00:00:00', end: '23:59:00' },
        });
    }
    await api(`/services/${service.id}/requirements`, {
        token,
        method: 'POST',
        body: { type: 'PERSON', quantity: 1 },
    });
    return { serviceId: service.id, name: 'Coupe' };
}

/** Books an appointment for today on [serviceId], as the anonymous client. */
async function bookToday(
    token: string,
    serviceId: string,
    clientName: string,
    clientPhone: string,
): Promise<void> {
    const link = await api<{ token: string }>(`/services/${serviceId}/booking-link`, { token });
    const today = new Date().toISOString().slice(0, 10);
    const view = await api<{ slots: { startsAt: string }[] }>(`/appointments/${link.token}?date=${today}`);
    const slot = view.slots[0];
    if (!slot) {
        throw new Error('No bookable slot today — near midnight the grid rolls to tomorrow');
    }
    await api(`/appointments/${link.token}/book`, {
        method: 'POST',
        body: { clientName, clientPhone, startsAt: slot.startsAt },
    });
}

test('serves a booked appointment and a walk-in client on the same day', async ({ page }) => {
    const organizer = await onboardedOrganizer(page, 'dayline');
    const token = await login(organizer);

    const service = await createDayService(token);
    await bookToday(token, service.serviceId, 'Fatou Camara', '+224611111111');

    // The organizer opens the day-line console of the service.
    await page.goto(`/services/${service.serviceId}/line`);
    await expect(page.getByRole('heading', { name: 'Ligne du jour' })).toBeVisible();
    await expect(page.getByText('Coupe').first()).toBeVisible();

    // A new service confirms bookings on request: the rendez-vous waits for the
    // organizer's decision, then joins the line once confirmed.
    await expect(page.getByText('Demandes en attente')).toBeVisible();
    await page.getByRole('button', { name: 'Confirmer' }).click();

    // The booked rendez-vous is on the line, not arrived yet: it can be marked arrived.
    await expect(page.getByText('Fatou Camara', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Arrivée' }).click();
    await expect(page.getByRole('button', { name: 'Appeler' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Arrivée' })).toHaveCount(0);

    // A walk-in arrives at the counter and joins the same line, interleaved.
    await page.getByRole('button', { name: '+ Sans RDV' }).click();
    await page.locator('#walkin-name').fill('Aïssatou Barry');
    await page.locator('#walkin-phone').fill('+224622222222');
    await page.getByRole('button', { name: 'Ajouter à la file' }).click();
    await expect(page.getByText('Aïssatou Barry', { exact: true })).toBeVisible();

    // SUIVANT applies the rule: the rendez-vous arrived first is the longest wait.
    await page.getByRole('button', { name: 'SUIVANT' }).click();
    await expect(page.getByRole('button', { name: 'Prendre en charge' })).toBeVisible();

    // The rendez-vous is taken in charge and finished.
    await page.getByRole('button', { name: 'Prendre en charge' }).click();
    await page.getByRole('button', { name: 'Terminer' }).click();
    await expect(page.getByText('Fatou Camara', { exact: true })).toBeVisible();

    // SUIVANT now calls the walk-in, which is served the same way.
    await page.getByRole('button', { name: 'SUIVANT' }).click();
    await expect(page.getByRole('button', { name: 'Prendre en charge' })).toBeVisible();
    await page.getByRole('button', { name: 'Prendre en charge' }).click();
    await page.getByRole('button', { name: 'Terminer' }).click();

    // Everyone has been served: nobody is left to call.
    await page.getByRole('button', { name: 'SUIVANT' }).click();
    await expect(page.getByRole('button', { name: 'Appeler' })).toHaveCount(0);
});

test('a walk-in client takes a ticket from the entrance QR and is told when it is their turn', async ({ page }) => {
    const organizer = await onboardedOrganizer(page, 'selfline');
    const token = await login(organizer);
    const service = await createDayService(token);
    const link = await api<{ shortCode: string }>(`/services/${service.serviceId}/booking-link`, { token });

    // The entrance QR leads the client, with no account, to take a ticket. The
    // client has no locale cookie, so the page follows the browser's English.
    await page.goto(`/r/${link.shortCode}/line`);
    await expect(page.getByText('Take a ticket')).toBeVisible();
    await page.locator('#line-name').fill('Mariama Diallo');
    await page.locator('#line-phone').fill('+224620112233');
    await page.getByRole('button', { name: 'Take my ticket' }).click();

    // Their own page: their number and their place, nobody else's.
    await expect(page.getByText('Ticket no. 1')).toBeVisible();
    await expect(page.getByText("You're next.")).toBeVisible();

    // The counter calls the next client; the client's page follows on its own.
    await api(`/services/${service.serviceId}/day-line/next?counter=${encodeURIComponent('guichet 4')}`, {
        token,
        method: 'POST',
    });
    await expect(page.getByText("It's your turn!")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Please go to guichet 4.')).toBeVisible();
});
