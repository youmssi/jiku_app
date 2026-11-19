import { expect, test } from '@playwright/test';

import { createQuickService, login, serviceCardCode, setServiceQuestions } from '../fixtures/api';
import { onboardedOrganizer } from '../fixtures/organizer';
import { inEnglish } from '../fixtures/locale';

/**
 * Guided questions (JIKU-191): the booking page asks the service's questions
 * before the times; an answer that belongs to another service leads there, and
 * the times only show once every question has an answer for this service.
 */
test('the booking page asks the questions before the times @smoke', async ({ page, browser }) => {
    const organizer = await onboardedOrganizer(page, 'questions');
    const token = await login(organizer);
    const serviceId = await createQuickService(token, 'Dental care', 30);
    const scaling = await createQuickService(token, 'Scaling', 30);
    await setServiceQuestions(token, serviceId, [
        { prompt: 'What do you need?', choices: [{ label: 'Pain' }, { label: 'Check-up' }, { label: 'Scaling', routeServiceId: scaling }] },
        { prompt: 'First visit?', choices: [{ label: 'Yes' }, { label: 'No' }] },
    ]);
    const code = await serviceCardCode(token, serviceId);

    const client = await browser.newContext();
    const booking = await client.newPage();
    await booking.goto(inEnglish(`/r/${code}`));
    await expect(booking.getByText('What do you need?')).toBeVisible();
    await expect(booking.getByRole('button', { name: 'See the times' })).toHaveCount(0);

    // An answer that has its own service leads there instead.
    await booking.getByRole('radio', { name: 'Scaling' }).click();
    await expect(booking.getByText('This need has its own service')).toBeVisible();
    await expect(booking.getByRole('link', { name: 'Book that service' })).toBeVisible();

    await booking.getByRole('radio', { name: 'Pain' }).click();
    await booking.getByRole('radio', { name: 'Yes' }).click();
    await booking.getByRole('button', { name: 'See the times' }).click();
    await expect(booking.getByText('Pain · Yes')).toBeVisible();
    await expect(booking.getByRole('listbox')).toBeVisible();
    await client.close();
});
