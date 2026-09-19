'use strict';
const $ = id => document.getElementById(id);
const scenarios = [
    ['SUCCESS', 'Successful payment', 'Success', 'success', 'The provider accepts the deposit immediately.'],
    ['PENDING', 'Pending → webhook', 'Pending', 'pending', 'The provider waits. Send a webhook to resolve the payment.'],
    ['TIMEOUT', 'Timeout → recovery', 'Recovery', 'unknown', 'The charge succeeds, but the reply arrives too late. Recovery reuses the same operation.'],
    ['SOFT_THEN_SUCCESS', 'Soft decline → success', 'Auto retry', 'pending', 'Issuer unavailable twice. The third attempt succeeds, with no extra clicks.'],
    ['INSUFFICIENT_FUNDS', 'Insufficient funds', 'Soft decline', 'pending', 'Retries up to 3 total attempts, then fails. Short delays are for this test application.'],
    ['ISSUER_UNAVAILABLE', 'Issuer unavailable', 'Soft decline', 'pending', 'Temporary issuer failure. Retries after 3 and 6 seconds, then fails.'],
    ['PROCESSING_ERROR', 'Processing error', 'Soft decline', 'pending', 'A definitive temporary refusal. Retries up to 3 total attempts.'],
    ['EXPIRED_CARD', 'Expired card', 'Hard decline', 'failed', 'Fails immediately. No automatic retries.'],
    ['INVALID_CARD', 'Invalid card number', 'Hard decline', 'failed', 'Fails immediately. No automatic retries.'],
    ['LOST_CARD', 'Lost card', 'Hard decline', 'failed', 'Fails immediately. No automatic retries.'],
    ['STOLEN_CARD', 'Stolen card', 'Hard decline', 'failed', 'Fails immediately. No automatic retries.'],
    ['DO_NOT_HONOR', 'Do not honor', 'Hard decline', 'failed', 'Conservative policy: no retry without a more specific issuer instruction.'],
    ['FAILED', 'Provider failure', 'Failed', 'failed', 'The provider definitively refuses the payment with DO_NOT_HONOR.']
];
const labels = {
    SUCCESS: 'Succeeded',
    FAILED: 'Failed',
    PENDING: 'Pending',
    PROCESSING: 'Processing',
    UNKNOWN: 'Recovering',
    RETRY_SCHEDULED: 'Retry scheduled'
};
let payments = [], selected = null, filter = 'ALL', lastRequest = null, busy = false, toastTimer;
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const badge = status => `<span class="badge ${String(status).toLowerCase()}">${esc(labels[status] || status)}</span>`;
const time = s => new Date(s).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit', second: '2-digit'});
const newKey = () => $('key').value = crypto.randomUUID();

function toast(message) {
    $('toast').textContent = message;
    $('toast').hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => $('toast').hidden = true, 4200);
}

function setBusy(value) {
    busy = value;
    document.querySelectorAll('#deposit-form button,.experiment-grid button,#webhook').forEach(b => b.disabled = value);
    $('create-button').innerHTML = value ? 'Sending request…' : 'Create deposit <span>↗</span>';
}

function readRequest() {
    return {
        body: {
            merchantId: $('merchant').value,
            orderId: $('order').value,
            amount: Number($('amount').value),
            currency: $('currency').value,
            paymentMethod: 'CARD'
        }, key: $('key').value, scenario: $('scenario').value
    };
}

function logResponse(label, response, body) {
    const wrap = document.createElement('details');
    wrap.className = 'response-item';
    wrap.open = !response.ok;
    wrap.innerHTML = `<summary><span class="http-code ${response.ok ? '' : 'error'}">${response.status}</span><span>${esc(label)}</span>${response.headers.get('Idempotency-Replayed') === 'true' ? '<span class="badge processing">Replayed</span>' : ''}<time>${time(Date.now())}</time></summary><pre></pre>`;
    wrap.querySelector('pre').textContent = JSON.stringify(body, null, 2);
    if ($('responses').querySelector('.helper')) $('responses').replaceChildren();
    $('responses').prepend(wrap);
    while ($('responses').children.length > 20) $('responses').lastChild.remove();
}

async function request(path, body, headers, label) {
    const response = await fetch(path, {
        method: 'POST',
        headers: {'Content-Type': 'application/json', ...headers},
        body: JSON.stringify(body)
    });
    const result = await response.json();
    logResponse(label, response, result);
    if (result.transactionId) selected = result.transactionId;
    return {response, result};
}

async function sendDeposit(snapshot, label) {
    return request('/api/v1/payments/deposit', snapshot.body, {
        'Idempotency-Key': snapshot.key,
        'X-Provider-Scenario': snapshot.scenario
    }, label);
}

async function action(run) {
    if (busy) return;
    setBusy(true);
    try {
        await run();
        await refresh();
    } catch (e) {
        toast('Could not reach the service. Check the connection and retry with the same key.');
    } finally {
        setBusy(false);
    }
}

$('deposit-form').addEventListener('submit', e => {
    e.preventDefault();
    action(async () => {
        lastRequest = structuredClone(readRequest());
        await sendDeposit(lastRequest, 'Create deposit');
    });
});
$('new-key').onclick = newKey;
$('scenario').innerHTML = scenarios.map(s => `<option value="${s[0]}">${s[1]}</option>`).join('');
$('scenario').onchange = () => {
    const s = scenarios.find(s => s[0] === $('scenario').value);
    $('scenario-badge').className = 'badge ' + s[3];
    $('scenario-badge').textContent = s[2];
    $('scenario-description').textContent = s[4];
};
$('repeat').onclick = () => {
    if (!lastRequest) return toast('Create a deposit first.');
    action(() => sendDeposit(lastRequest, 'Repeat original request'));
};
$('concurrent').onclick = () => action(async () => {
    newKey();
    lastRequest = structuredClone(readRequest());
    const responses = await Promise.all([sendDeposit(lastRequest, 'Concurrent request A'), sendDeposit(lastRequest, 'Concurrent request B')]);
    const ids = responses.map(r => r.result.transactionId);
    if (ids[0] && ids[0] === ids[1]) toast('Two requests. One transaction.');
});
$('conflict').onclick = () => {
    if (!lastRequest) return toast('Create a deposit first.');
    action(() => {
        const changed = structuredClone(lastRequest);
        changed.body.amount = Number((changed.body.amount + 1).toFixed(2));
        return sendDeposit(changed, 'Same key · different amount');
    });
};
$('invalid').onclick = () => action(() => {
    const invalid = readRequest();
    invalid.key = crypto.randomUUID();
    invalid.body.amount = -10;
    return sendDeposit(invalid, 'Invalid amount · −10');
});
$('webhook').onclick = () => action(async () => {
    const p = payments.find(p => p.transactionId === selected);
    if (!p?.providerTransactionId) return toast('The provider operation is not assigned yet.');
    await request('/api/v1/payments/webhook', {
        providerTransactionId: p.providerTransactionId,
        status: $('webhook-status').value
    }, {}, 'Webhook · ' + $('webhook-status').value);
});
$('clear-log').onclick = () => {
    $('responses').innerHTML = '<p class="helper">HTTP responses appear here when you run a scenario.</p>';
};
$('refresh').onclick = () => refresh();
$('search').oninput = renderPayments;
document.querySelectorAll('[data-filter]').forEach(b => b.onclick = () => {
    filter = b.dataset.filter;
    document.querySelectorAll('[data-filter]').forEach(x => x.classList.toggle('active', x === b));
    renderPayments();
});

function renderPayments() {
    $('total').textContent = payments.length;
    $('succeeded').textContent = payments.filter(p => p.status === 'SUCCESS').length;
    $('failed').textContent = payments.filter(p => p.status === 'FAILED').length;
    $('pending').textContent = payments.filter(p => !['SUCCESS', 'FAILED'].includes(p.status)).length;
    const search = $('search').value.toLowerCase();
    const visible = payments.filter(p => (filter === 'ALL' || p.status === filter || (filter === 'ACTIVE' && !['SUCCESS', 'FAILED'].includes(p.status))) && `${p.orderId} ${p.transactionId} ${p.merchantId}`.toLowerCase().includes(search));
    $('payments').innerHTML = visible.map(p => `<tr tabindex="0" data-id="${p.transactionId}" class="${p.transactionId === selected ? 'selected' : ''}" aria-label="Open ${esc(p.orderId)}"><td>${esc(p.orderId)}<small>${esc(p.merchantId)} · ${time(p.createdAt)}</small></td><td>${esc(new Intl.NumberFormat('en-GB', {
        style: 'currency',
        currency: p.currency
    }).format(p.amount))}</td><td>${badge(p.status)}</td><td class="attempt-col">${p.attemptCount}<small>of 3</small></td></tr>`).join('');
    $('empty').hidden = visible.length > 0;
    $('empty').querySelector('h3').textContent = payments.length ? 'No matching payments.' : 'A clean slate.';
    $('empty').querySelector('p').innerHTML = payments.length ? 'Try another filter or search.' : 'Your payments will appear here.<br>Start with a successful deposit.';
    $('list-count').textContent = `${visible.length} payment${visible.length === 1 ? '' : 's'}`;
    document.querySelectorAll('[data-id]').forEach(row => {
        const pick = () => {
            selected = row.dataset.id;
            renderPayments();
            renderDetail().catch(() => toast('Could not load this payment.'));
        };
        row.onclick = pick;
        row.onkeydown = e => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                pick();
            }
        };
    });
}

async function renderDetail() {
    if (!selected) return;
    const id = selected;
    const response = await fetch('/api/v1/payments/' + id);
    if (!response.ok) throw new Error();
    const {payment: p, events} = await response.json();
    if (selected !== id) return;
    $('detail-empty').hidden = true;
    $('detail-content').hidden = false;
    $('detail-title').textContent = p.orderId;
    $('detail-title').hidden = false;
    $('detail-status').innerHTML = badge(p.status);
    const meta = [['Transaction', p.transactionId], ['Provider', p.providerTransactionId || 'Not assigned'], ['Idempotency key', p.idempotencyKey], ['Scenario', p.scenario], ['Decline', p.declineCode || '—'], ['Next attempt', p.nextAttemptAt ? new Date(p.nextAttemptAt).toLocaleString() : '—']];
    $('detail-meta').innerHTML = meta.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('');
    $('timeline').innerHTML = events.map(e => `<li><strong>${esc(labels[e.status])}</strong><p>${esc(e.message)}</p><time>${time(e.createdAt)}</time></li>`).join('');
}

let refreshing = false, refreshFailed = false;

async function refresh() {
    if (refreshing) return;
    refreshing = true;
    try {
        const response = await fetch('/api/v1/payments');
        if (!response.ok) throw new Error();
        payments = await response.json();
        refreshFailed = false;
        renderPayments();
        await renderDetail();
    } catch (e) {
        if (!refreshFailed) toast('Could not refresh payments. Check the connection.');
        refreshFailed = true;
    } finally {
        refreshing = false;
    }
}

newKey();
$('scenario').onchange();
refresh();
setInterval(() => {
    if (!document.hidden) refresh();
}, 2000);
