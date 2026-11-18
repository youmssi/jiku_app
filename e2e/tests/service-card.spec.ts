import { expect, test } from '@playwright/test';

import { createClosedService, createQuickService, login, serviceCardCode, setCardMode } from '../fixtures/api';
import { onboardedOrganizer } from '../fixtures/organizer';
import { inEnglish } from '../fixtures/locale';

/**
 * Service card (JIKU-189): the organizer sets the place shown on the card in the
 * service's Share tab, a client opens the booking page from the card, and the
 * open shows in the card's results. A new organization is not verified, so the
 * card is a preview with the way to get verified instead of the share buttons.
 */
test('counts a booking page opened from the service card @smoke', async ({ page, browser }) => {
    const organizer = await onboardedOrganizer(page, 'card');
    const token = await login(organizer);
    const serviceId = await createQuickService(token, 'Coupe homme', 30);
    const code = await serviceCardCode(token, serviceId);

    await page.goto(inEnglish(`/services/${serviceId}/share`));
    await expect(page.getByText('Service card and link')).toBeVisible();
    await expect(page.getByText('Get your organization verified to share this card.')).toBeVisible();
    await expect(page.getByRole('img', { name: 'Card for Coupe homme' })).toBeVisible();

    // The form only reacts once the page is interactive.
    await page.waitForLoadState('networkidle');
    const place = page.getByLabel('Place shown on the card');
    await place.fill('Kaloum, Conakry');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByText('Place saved')).toBeVisible();

    // The client opens the booking page from the card's QR code, in a browser of their own.
    const client = await browser.newContext();
    const booking = await client.newPage();
    const opened = booking.waitForResponse((response) => response.request().method() === 'POST' && response.url().includes(`/r/${code}`));
    await booking.goto(inEnglish(`/r/${code}?src=card`));
    await expect(booking.getByRole('heading', { name: 'Coupe homme' })).toBeVisible();
    await opened;
    await client.close();

    await page.reload();
    const opens = page.getByText('Opens from the card').locator('..');
    await expect(opens).toContainText('1');
});

/**
 * Slot mode (JIKU-190): a card that leads straight to the times opens on the first
 * free day; with no free time this week the page shows the presentation instead.
 */
test('a card in slot mode opens on the times, or says none this week', async ({ page, browser }) => {
    const organizer = await onboardedOrganizer(page, 'slotcard');
    const token = await login(organizer);
    const open = await createQuickService(token, 'Coupe enfant', 30);
    const closed = await createClosedService(token, 'Fermé');
    await setCardMode(token, open, 'SLOT');
    await setCardMode(token, closed, 'SLOT');
    const openCode = await serviceCardCode(token, open);
    const closedCode = await serviceCardCode(token, closed);

    const client = await browser.newContext();
    const booking = await client.newPage();
    await booking.goto(inEnglish(`/r/${openCode}?src=card&step=slot`));
    await expect(booking.getByRole('heading', { name: 'Coupe enfant' })).toBeVisible();
    // The day strip lands on a day with free times, and the times are offered.
    await expect(booking.getByText('No times open on this day.')).toHaveCount(0);
    await expect(booking.getByText('No times this week')).toHaveCount(0);

    await booking.goto(inEnglish(`/r/${closedCode}?src=card&step=slot`));
    await expect(booking.getByText('No times this week')).toBeVisible();
    await client.close();
});
