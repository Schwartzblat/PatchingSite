import {useEffect, useState} from 'react';
import {api} from './api.js';

/*
 * The Pro price, from Stripe via GET /api/subscription/price. It used to be a
 * hardcoded "$4" in Subscription.jsx, which would silently lie the day the
 * Stripe price changed. When the endpoint fails (503 while Stripe is down)
 * the amount is hidden rather than guessed.
 *
 * One request per page load, shared by every component that asks: the server
 * caches it for ~10 minutes anyway. A failure is not cached, so the next
 * page that mounts tries again.
 */
let pending = null;

function loadPrice() {
    if (!pending) {
        pending = api.getPrice().catch((err) => {
            pending = null;
            throw err;
        });
    }
    return pending;
}

function isPrice(value) {
    return Number.isInteger(value?.amount) && value.amount >= 0
        && typeof value?.currency === 'string' && /^[a-z]{3}$/i.test(value.currency);
}

/** `undefined` while loading, `null` when unavailable, else the price object. */
export function usePrice() {
    const [price, setPrice] = useState(undefined);

    useEffect(() => {
        let live = true;
        loadPrice()
            .then((value) => {
                if (live) setPrice(isPrice(value) ? value : null);
            })
            .catch(() => {
                if (live) setPrice(null);
            });
        return () => {
            live = false;
        };
    }, []);

    return price;
}

const PERIOD_UNITS = {day: 'day', week: 'week', month: 'month', year: 'year'};
/* Hebrew reads "$4 לחודש" ("$4 a month"); [singular, plural] per unit. */
const PERIOD_UNITS_HE = {day: ['יום', 'ימים'], week: ['שבוע', 'שבועות'], month: ['חודש', 'חודשים'], year: ['שנה', 'שנים']};

/**
 * {amount: "$4", period: "/month"}, or null when there is nothing trustworthy
 * to print. `amount` is in minor units, so the divisor comes from the
 * currency itself (2 decimals for USD, 0 for JPY). Whole amounts drop the
 * ".00", which is how a price reads on a pricing card. `lang` words the period;
 * the amount keeps the en-US format ("$4") in both languages.
 */
export function formatPrice(price, lang = 'en') {
    if (!isPrice(price)) return null;
    try {
        const currency = price.currency.toUpperCase();
        const digits = new Intl.NumberFormat('en-US', {style: 'currency', currency})
            .resolvedOptions().maximumFractionDigits;
        const whole = price.amount % 10 ** digits === 0;
        const amount = new Intl.NumberFormat('en-US', {
            style: 'currency',
            currency,
            minimumFractionDigits: whole ? 0 : digits,
            maximumFractionDigits: digits,
        }).format(price.amount / 10 ** digits);

        const count = Number.isInteger(price.interval_count) && price.interval_count > 1 ? price.interval_count : 1;
        let period = '';
        if (lang === 'he') {
            const [one, many] = PERIOD_UNITS_HE[price.interval] ?? [];
            if (one) period = count === 1 ? ` ל${one}` : ` כל ${count} ${many}`;
        } else {
            const unit = PERIOD_UNITS[price.interval];
            if (unit) period = count === 1 ? `/${unit}` : ` every ${count} ${unit}s`;
        }
        return {amount, period};
    } catch {
        // Intl throws RangeError for a currency code it does not know.
        return null;
    }
}
