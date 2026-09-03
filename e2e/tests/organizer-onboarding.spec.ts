import { expect, test } from '@playwright/test';

import { newOrganizer, createOrganization, register, submitRegistration, verifyEmail } from '../fixtures/organizer';

/**
 * Journey 1: a stranger becomes an organizer who can run an event.
 *
 * This is the path every paying customer walks exactly once, and until now no
 * test had ever walked it end to end — registration, a real verification email,
 * and organization creation each had unit coverage on their own side of the
 * stack while the wiring between them had none (JIKU-71).
 */
test.describe('Organizer onboarding', () => {
    test('registers, verifies by email, and creates an organization @smoke', async ({ page }) => {
        const organizer = newOrganizer('onboarding');

        await register(page, organizer);
        // Registration signs the user in but leaves the address unverified; the
        // product refuses organization creation until it is confirmed.
        await expect(page).not.toHaveURL(/\/register$/);

        await verifyEmail(page, organizer);
        await expect(page.getByText(/verified|vérifié/i).first()).toBeVisible();

        await createOrganization(page, organizer);
        await expect(page).toHaveURL(/\/dashboard/);
        await expect(page.getByText(organizer.organization).first()).toBeVisible();
    });

    test('refuses a duplicate email instead of creating a second account', async ({ page }) => {
        const organizer = newOrganizer('duplicate');
        await register(page, organizer);
        await verifyEmail(page, organizer);

        // The second attempt must be refused, so it is submitted without waiting
        // for the navigation a successful registration would cause.
        await submitRegistration(page, organizer);
        await expect(page.getByText(/already|exist|déjà/i).first()).toBeVisible();
    });
});
