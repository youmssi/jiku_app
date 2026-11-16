/**
 * The suite asserts English copy, but the site serves French on unprefixed paths
 * whatever the browser asks for (JIKU-310). Every navigation therefore goes
 * through the English prefix; links that already carry it are left as they are.
 */
const ENGLISH = '/en';

export function inEnglish(url: string): string {
    const parsed = new URL(url, 'http://relative.invalid');
    if (parsed.pathname === ENGLISH || parsed.pathname.startsWith(`${ENGLISH}/`)) return url;
    parsed.pathname = `${ENGLISH}${parsed.pathname === '/' ? '' : parsed.pathname}`;
    return url.startsWith('/') ? `${parsed.pathname}${parsed.search}${parsed.hash}` : parsed.toString();
}
