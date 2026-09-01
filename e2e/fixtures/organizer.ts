import { expect, type Page } from '@playwright/test';

import { extractLink, waitForMessage } from './mailpit';

/**
 * Registers a verified organizer with an organization, driving the real UI.
 *
 * Registration is unique-by-email, so every run gets a fresh address rather than
 * reusing a fixture account — otherwise a second run would collide with the first
 * and fail for a reason that has nothing to do with the change under test.
 */

export interface Organizer {
    fullName: string;
    email: string;
    password: string;
    organization: string;
}

export function newOrganizer(label: string): Organizer {
    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    return {
        fullName: 'E2E Organizer',
        email: `e2e-${label}-${unique}@test.example`,
        password: 'supersecret123',
        organization: `E2E ${label} ${unique.slice(-6)}`,
    };
}

/**
 * Fills the registration form and waits for the resulting navigation. Field ids
 * mirror the form's field names.
 *
 * The wait is load-bearing: registration signs the user in through an httpOnly
 * cookie set on the action's response, and leaving the page before it lands
 * loses the session. The next page then bounces to the sign-in screen, which
 * surfaces much later as an unrelated-looking missing field.
 */
export async function register(page: Page, organizer: Organizer): Promise<void> {
    await submitRegistration(page, organizer);
    await page.waitForURL((url) => !url.pathname.endsWith('/register'), { timeout: 60_000 });
}

/**
 * Submits the form without waiting for success — for the cases that expect the
 * attempt to be refused and the user to stay put.
 */
export async function submitRegistration(page: Page, organizer: Organizer): Promise<void> {
    await page.goto('/register');
    await page.locator('#fullName').fill(organizer.fullName);
    await page.locator('#email').fill(organizer.email);
    await page.locator('#password').fill(organizer.password);
    await page.getByRole('button', { name: /create|sign up|register/i }).click();
}

/**
 * Follows the verification link out of the real email. An address must be
 * verified before an organization can be created, so this is a prerequisite for
 * every organizer journey rather than a test of its own.
 */
export async function verifyEmail(page: Page, organizer: Organizer): Promise<void> {
    const message = await waitForMessage(organizer.email, { subjectContains: 'Verify' });
    const link = extractLink(message, '/verify-email');
    await page.goto(link);
    // Waiting for the confirmation is load-bearing, not cosmetic: the page
    // redeems the token after it renders, and onboarding shows a "verify your
    // address first" notice — with no organization field — until that lands.
    await expect(page.getByText(/verified|vérifié/i).first()).toBeVisible({ timeout: 30_000 });
}

/**
 * Creates the organization and waits for the dashboard before returning.
 *
 * The wait is load-bearing, not cosmetic: a token minted before the organization
 * exists carries no tenant, and every tenant-scoped call made with it fails.
 * Returning on the click alone let callers race ahead and produced an
 * intermittent failure in the first API call after onboarding.
 */
export async function createOrganization(page: Page, organizer: Organizer): Promise<void> {
    await page.goto('/onboarding');
    await page.locator('#name').fill(organizer.organization);
    await page.getByRole('button', { name: /create|continue/i }).click();
    await page.waitForURL(/\/dashboard/, { timeout: 30_000 });
}

/** The full path from nothing to an organizer who can create an event. */
export async function onboardedOrganizer(page: Page, label: string): Promise<Organizer> {
    const organizer = newOrganizer(label);
    await register(page, organizer);
    await verifyEmail(page, organizer);
    await createOrganization(page, organizer);
    return organizer;
}
