import { expect, test } from '@playwright/test';

import { createEvent, importGuests, listGuests, login, publishEvent, sendInvitations } from '../fixtures/api';
import { extractLink, waitForMessage } from '../fixtures/mailpit';
import { onboardedOrganizer } from '../fixtures/organizer';

/**
 * Journeys 2 and 3: guests are imported and invited, and a guest turns their
 * invitation into a ticket.
 *
 * These are sequential by nature — nobody can RSVP to an invitation that was
 * never sent — so one organizer and one event carry both. The invitation is read
 * out of Mailpit and its link followed, so a broken template or a wrong
 * SERVER_BASE_URL fails here rather than in a customer's inbox (JIKU-71).
 */
test.describe('Invitation and RSVP', () => {
    test('imports guests, sends invitations, and a guest confirms and gets a ticket @smoke', async ({ page }) => {
        const organizer = await onboardedOrganizer(page, 'rsvp');
        const token = await login(organizer);

        const eventId = await createEvent(token, 'E2E Ceremony');
        await publishEvent(token, eventId);

        const guestEmail = `e2e-guest-${Date.now()}@test.example`;
        await importGuests(token, eventId, [
            { firstName: 'Awa', lastName: 'Camara', email: guestEmail },
            { firstName: 'Mamadou', lastName: 'Diallo', email: `e2e-guest2-${Date.now()}@test.example` },
        ]);

        const guests = await listGuests(token, eventId);
        expect(guests).toHaveLength(2);

        await sendInvitations(token, eventId);

        // Journey 3 starts here: follow the real invitation out of the real inbox.
        const invitation = await waitForMessage(guestEmail, { timeoutMs: 60_000 });
        // The personal link is a short code (JIKU-248), not a long signed token.
        const link = extractLink(invitation, '/v/');
        expect(new URL(link).pathname).toMatch(/^\/(en\/)?v\/[A-Z0-9]{10}$/);

        await page.goto(link);
        await page.getByRole('button', { name: "I'm coming" }).click();

        // A yes must actually issue a ticket, shown right away on the same link.
        // The QR is rendered client-side from the ticket code; it is the only
        // thing a validator can actually scan, so its absence is a failure even
        // when the rest of the page looks right.
        await expect(page.getByText('Valid', { exact: true })).toBeVisible();
        await expect(page.locator('canvas, svg').first()).toBeVisible();

        // Opening the same link again goes straight to the ticket.
        await page.goto(link);
        await expect(page.getByText('Valid', { exact: true })).toBeVisible();
    });

    test('a guest who declines is not given a ticket', async ({ page }) => {
        const organizer = await onboardedOrganizer(page, 'decline');
        const token = await login(organizer);

        const eventId = await createEvent(token, 'E2E Declined Ceremony');
        await publishEvent(token, eventId);

        const guestEmail = `e2e-decline-${Date.now()}@test.example`;
        await importGuests(token, eventId, [{ firstName: 'Fatou', lastName: 'Barry', email: guestEmail }]);
        await sendInvitations(token, eventId);

        const invitation = await waitForMessage(guestEmail, { timeoutMs: 60_000 });
        await page.goto(extractLink(invitation, '/v/'));

        await page.getByRole('button', { name: "I can't come" }).click();
        await expect(page.getByText(/You've declined/i).first()).toBeVisible();
        await expect(page.getByText('Valid', { exact: true })).toHaveCount(0);
    });
});
