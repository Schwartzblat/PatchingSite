/*
 * "October 30, 2026", or null for anything that is not a real date. The
 * backend used to hardcode currentPeriodEnd to null; `new Date(null)` is 1970
 * and `new Date(undefined)` is Invalid Date, so neither may be printed.
 */
export function formatDate(value) {
    if (value === null || value === undefined || value === '') return null;
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return null;
    return date.toLocaleDateString('en-US', {year: 'numeric', month: 'long', day: 'numeric'});
}

/*
 * The same, read in UTC. Free trials end at 00:00 UTC, so west of UTC
 * formatDate would name the day before.
 */
export function formatUtcDate(value) {
    if (value === null || value === undefined || value === '') return null;
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return null;
    return date.toLocaleDateString('en-US', {year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC'});
}
