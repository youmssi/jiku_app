import { expect, test, type Locator, type Page } from '@playwright/test';

import {
    createEvent,
    createValidator,
    importGuests,
    listGuests,
    login,
    publishEvent,
    sendInvitations,
} from '../fixtures/api';
import { extractLink, waitForMessage } from '../fixtures/mailpit';
import { onboardedOrganizer } from '../fixtures/organizer';

/**
 * Journey 4: door staff admit a guest, and the second attempt is refused with
 * enough detail to settle an argument at the entrance.
 *
 * The console's primary action is a camera QR scan, which no headless browser
 * can perform; the name-search fallback is the same check-in path with a
 * different way of resolving the guest, and it is the path staff actually use
 * when a phone screen will not scan (JIKU-71).
 */
test.describe('Validator check-in', () => {
    test('checks a guest in, then reports the duplicate with who and when @smoke', async ({ page }) => {
        const organizer = await onboardedOrganizer(page, 'checkin');
        const token = await login(organizer);

        const eventId = await createEvent(token, 'E2E Door Event');
        await publishEvent(token, eventId);

        const guestEmail = `e2e-door-${Date.now()}@test.example`;
        await importGuests(token, eventId, [{ firstName: 'Ibrahima', lastName: 'Sow', email: guestEmail }]);
        await sendInvitations(token, eventId);

        // Only a confirmed guest holds a ticket, so the guest confirms first.
        const invitation = await waitForMessage(guestEmail, { timeoutMs: 60_000 });
        await page.goto(extractLink(invitation, '/invitation/'));
        await page.getByRole('button', { name: 'Confirm attendance' }).click();
        await expect(page.getByText(/You're confirmed/i).first()).toBeVisible();

        const validatorLabel = 'Main gate';
        const checkInLink = await createValidator(token, eventId, validatorLabel);

        await page.goto(checkInLink);
        await page.getByRole('button', { name: 'Search', exact: true }).click();
        await page.getByPlaceholder('Search by name, email or phone').fill('Ibrahima');
        await page.getByRole('button', { name: /Ibrahima Sow/ }).click();

        // The result is a full-screen overlay; scoping to it keeps these
        // assertions off the search list rendered behind it.
        const admitted = resultOverlay(page, 'Checked in');
        await expect(admitted).toBeVisible();
        await expect(admitted.getByText('Ibrahima Sow')).toBeVisible();

        // Dismiss the result and admit the same guest again.
        await admitted.getByRole('button', { name: 'Scan next' }).click();
        await page.getByPlaceholder('Search by name, email or phone').fill('Ibrahima');
        await page.getByRole('button', { name: /Ibrahima Sow/ }).click();

        // A duplicate must not read as a generic failure: staff need to know who
        // admitted this guest and when, or they cannot resolve the dispute at the
        // door.
        const duplicate = resultOverlay(page, 'Already checked in');
        await expect(duplicate).toBeVisible();
        await expect(duplicate.getByText(new RegExp(`by ${validatorLabel}\\b.*\\bat\\b`, 'i'))).toBeVisible();
    });

    test('reports an unknown guest as not found', async ({ page }) => {
        const organizer = await onboardedOrganizer(page, 'notfound');
        const token = await login(organizer);

        const eventId = await createEvent(token, 'E2E Empty Door Event');
        await publishEvent(token, eventId);
        await importGuests(token, eventId, [
            { firstName: 'Kadiatou', lastName: 'Bah', email: `e2e-kb-${Date.now()}@test.example` },
        ]);
        expect(await listGuests(token, eventId)).toHaveLength(1);

        await page.goto(await createValidator(token, eventId, 'Side gate'));
        await page.getByRole('button', { name: 'Search', exact: true }).click();
        await page.getByPlaceholder('Search by name, email or phone').fill('Nobody Here');

        await expect(page.getByText('No matching guests.')).toBeVisible();
    });
});

/** The full-screen check-in result whose heading reads [outcome]. */
function resultOverlay(page: Page, outcome: string): Locator {
    return page
        .locator('[role="presentation"]')
        .filter({ has: page.getByRole('heading', { name: outcome, exact: true }) });
}
