import { expect, test } from '@playwright/test';

import { createQuickService, login, serviceCardCode } from '../fixtures/api';
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
