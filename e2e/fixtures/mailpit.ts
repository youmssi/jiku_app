/**
 * Reads mail out of the local Mailpit container.
 *
 * Email-dependent steps are exercised for real rather than stubbed: the
 * verification and invitation links the product actually sends are the ones the
 * tests follow, so a broken template or a wrong base URL fails the suite instead
 * of passing silently (JIKU-71).
 */

const MAILPIT_URL = process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025';

interface MailpitSummary {
    ID: string;
    To: { Address: string }[];
    Subject: string;
}

interface MailpitMessage {
    ID: string;
    Subject: string;
    Text: string;
    HTML: string;
}

async function json<T>(path: string): Promise<T> {
    const response = await fetch(`${MAILPIT_URL}${path}`);
    if (!response.ok) {
        throw new Error(`Mailpit ${path} responded ${response.status}. Is the container up?`);
    }
    return (await response.json()) as T;
}

/** Deletes every captured message, so a test only ever sees its own mail. */
export async function clearInbox(): Promise<void> {
    const response = await fetch(`${MAILPIT_URL}/api/v1/messages`, { method: 'DELETE' });
    if (!response.ok) {
        throw new Error(`Could not clear Mailpit: ${response.status}`);
    }
}

/**
 * Waits for a message addressed to [recipient], newest first. Polls because the
 * send is asynchronous — the API call that triggers it returns before the queue
 * has handed the message to SMTP.
 */
export async function waitForMessage(
    recipient: string,
    options: { subject?: RegExp; timeoutMs?: number } = {},
): Promise<MailpitMessage> {
    const deadline = Date.now() + (options.timeoutMs ?? 30_000);
    let lastSeen: string[] = [];

    while (Date.now() < deadline) {
        const inbox = await json<{ messages: MailpitSummary[] }>('/api/v1/messages?limit=100');
        lastSeen = inbox.messages.map((m) => `${m.To.map((t) => t.Address).join(',')} | ${m.Subject}`);
        const match = inbox.messages.find(
            (m) =>
                m.To.some((t) => t.Address.toLowerCase() === recipient.toLowerCase()) &&
                (!options.subject || options.subject.test(m.Subject)),
        );
        if (match) {
            return json<MailpitMessage>(`/api/v1/message/${match.ID}`);
        }
        await new Promise((r) => setTimeout(r, 500));
    }

    throw new Error(
        `No mail for ${recipient}${options.subject ? ` matching ${options.subject}` : ''} ` +
            `within the timeout. Inbox held:\n${lastSeen.join('\n') || '(empty)'}`,
    );
}

/**
 * Pulls the first link pointing at the frontend out of a message. Both the
 * verification and invitation mails carry exactly one such link.
 */
export function extractLink(message: MailpitMessage, mustContain: string): string {
    const body = `${message.Text ?? ''}\n${message.HTML ?? ''}`;
    const urls = body.match(/https?:\/\/[^\s"'<>)\]]+/g) ?? [];
    const link = urls.find((u) => u.includes(mustContain));
    if (!link) {
        throw new Error(
            `No link containing "${mustContain}" in "${message.Subject}". Found: ${urls.join(', ') || '(none)'}`,
        );
    }
    return link.replace(/&amp;/g, '&');
}
