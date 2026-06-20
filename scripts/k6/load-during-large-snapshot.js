// k6 stress: 20k req/min batendo /httpbin e /anything do gateway enquanto
// `make large-snapshot-test` (ROUTES=30000) é disparado em paralelo.
//
// O que esse teste valida:
//   - O gateway continua respondendo nas rotas reais (sem 5xx, sem timeouts)
//     enquanto o snapshot pesado de 30k rotas é publicado/recarregado em
//     cada réplica.
//   - O downstream (go-httpbin) também aguenta o request path normal
//     enquanto o control plane está sob carga de reload.
//
// Como rodar:
//   Terminal A – port-forward por POD (uma porta por réplica):
//     kubectl port-forward -n platform pod/<pod-1> 18080:8080
//     kubectl port-forward -n platform pod/<pod-2> 18081:8080
//
//   Terminal B – sobe o nginx LB (round-robin entre 18080 e 18081):
//     scripts/k6/start-nginx-lb.sh
//
//   Terminal C – dispara o k6 (default já bate em http://localhost:18000):
//     k6 run scripts/k6/load-during-large-snapshot.js
//
//   Terminal D – durante o teste, sobe o snapshot pesado:
//     ROUTES=30000 make large-snapshot-test
//
// Por que LB em vez de port-forward direto no Service? `kubectl port-forward
// svc/...` escolhe UMA réplica no handshake e gruda nela; sem o LB a carga
// não exerce as duas réplicas simultaneamente, e o teste perde o sentido.
//
// Variáveis de ambiente (opcionais):
//   BASE_URL    Default: http://localhost:18000   (nginx LB local – ver acima)
//   DURATION    Default: 5m
//   RPM         Default: 20000  (req/min total, dividido 50/50 entre as 2 rotas)
//   PRE_VUS     Default: 50      (VUs pré-alocados por cenário)
//   MAX_VUS     Default: 400     (teto de VUs por cenário se houver back-pressure)
//
// Critérios de falha (thresholds):
//   - http_req_failed < 1% por cenário
//   - http_req_duration p(95) < 500ms por cenário
//   - checks 100% (status 200)
//
// Se algum threshold quebrar durante a janela em que o large-snapshot-test
// estiver rodando, é sinal de regressão de disponibilidade.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18000';
const DURATION = __ENV.DURATION || '5m';
const RPM = parseInt(__ENV.RPM || '20000', 10);
const PRE_VUS = parseInt(__ENV.PRE_VUS || '50', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '400', 10);

// 20k req/min total = 10k/min por cenário. k6 só aceita inteiros em
// constant-arrival-rate, então usamos timeUnit=1m para deixar a conta exata.
const PER_SCENARIO_RPM = Math.floor(RPM / 2);

const errors5xx = new Counter('gateway_5xx');
const errors4xx = new Counter('gateway_4xx');
const errorsConn = new Counter('gateway_connection_errors');

export const options = {
    discardResponseBodies: true,
    // summaryTrendStats default só dá min/avg/med/max/p(90)/p(95). Pedimos
    // p(99) explicitamente porque é onde o reload pesado costuma aparecer.
    summaryTrendStats: ['min', 'avg', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
        httpbin: {
            executor: 'constant-arrival-rate',
            exec: 'hitHttpbin',
            rate: PER_SCENARIO_RPM,
            timeUnit: '1m',
            duration: DURATION,
            preAllocatedVUs: PRE_VUS,
            maxVUs: MAX_VUS,
            tags: { route: 'httpbin' },
        },
        anything: {
            executor: 'constant-arrival-rate',
            exec: 'hitAnything',
            rate: PER_SCENARIO_RPM,
            timeUnit: '1m',
            duration: DURATION,
            preAllocatedVUs: PRE_VUS,
            maxVUs: MAX_VUS,
            tags: { route: 'anything' },
        },
    },
    thresholds: {
        'http_req_failed{route:httpbin}':    [{ threshold: 'rate<0.01', abortOnFail: false }],
        'http_req_failed{route:anything}':   [{ threshold: 'rate<0.01', abortOnFail: false }],
        'http_req_duration{route:httpbin}':  ['p(95)<500'],
        'http_req_duration{route:anything}': ['p(95)<500'],
        'checks{route:httpbin}':             ['rate>0.99'],
        'checks{route:anything}':            ['rate>0.99'],
        'gateway_5xx':                       ['count<10'],
    },
};

function hit(url, tag) {
    const res = http.get(url, {
        tags: { route: tag },
        timeout: '10s',
    });
    const ok = check(res, {
        'status is 200': (r) => r.status === 200,
    }, { route: tag });

    if (!ok) {
        if (res.status === 0) {
            errorsConn.add(1, { route: tag });
        } else if (res.status >= 500) {
            errors5xx.add(1, { route: tag });
        } else if (res.status >= 400) {
            // 404 esperado quando o snapshot de stress (30k rotas) substitui
            // o snapshot real e remove temporariamente /httpbin e /anything.
            // Separamos do 5xx para não confundir indisponibilidade do
            // gateway com remoção legítima da rota.
            errors4xx.add(1, { route: tag });
        }
    }

    return res;
}

export function hitHttpbin() {
    hit(`${BASE_URL}/httpbin/get`, 'httpbin');
}

export function hitAnything() {
    hit(`${BASE_URL}/anything/load-test`, 'anything');
}

export function setup() {
    // Sanity-check: o LB precisa estar de pé, e as duas rotas precisam estar
    // publicadas antes de começar a carga; caso contrário o teste vira só
    // uma medição de 404/502.
    const lbHealth = http.get(`${BASE_URL}/lb/health`, { timeout: '5s' });
    if (lbHealth.status !== 200) {
        throw new Error(
            `Pré-condição falhou: nginx LB em ${BASE_URL} respondeu HTTP ${lbHealth.status} ` +
            `no /lb/health (esperado 200). Suba com scripts/k6/start-nginx-lb.sh ` +
            `e confirme os port-forwards 18080/18081 dos pods.`
        );
    }

    const probes = [
        { url: `${BASE_URL}/httpbin/get`,        name: '/httpbin' },
        { url: `${BASE_URL}/anything/load-test`, name: '/anything' },
    ];
    for (const p of probes) {
        const r = http.get(p.url, { timeout: '5s' });
        if (r.status !== 200) {
            throw new Error(
                `Pré-condição falhou: ${p.name} respondeu HTTP ${r.status} ` +
                `(esperado 200). Garanta que as duas GatewayRoutes estão aplicadas ` +
                `e que os port-forwards dos pods (18080, 18081) estão ativos.`
            );
        }
    }
    return { startedAt: new Date().toISOString() };
}