// Counts what a raw client really gets from a route: one GET per fresh TCP connection,
// then classifies the answer.
//
// Why this exists: the App on the phone reported net::ERR_EMPTY_RESPONSE through the frp
// route. "Empty" is a different failure from "refused": the TCP handshake succeeded and the
// server sent zero bytes, which a browser hides (it retries) and a one-shot WebView does not.
// So a single tunnel-check PASS is not enough to say a route is usable - the rate matters.
//
//   node tools/tunnel-hammer.cjs [host] [port] [count] [key]
//
// Defaults: 47.76.59.5 8443 20 <tools/.bridge-key>. Prints one line per attempt plus a tally.
const fs = require('fs');
const net = require('net');
const path = require('path');

const host = process.argv[2] || '47.76.59.5';
const port = Number(process.argv[3] || 8443);
const count = Number(process.argv[4] || 20);
const key = (
  process.argv[5] ||
  fs.readFileSync(path.join(__dirname, '.bridge-key'), 'utf8')
).trim();
const who = host + ':' + port;
const timeoutMs = 8000;

function once(i) {
  return new Promise((resolve) => {
    const started = Date.now();
    const sock = net.connect({ host: host, port: port });
    let bytes = 0;
    let head = '';
    let settled = false;
    const done = (kind, extra) => {
      if (settled) return;
      settled = true;
      sock.destroy();
      resolve({ i: i, kind: kind, ms: Date.now() - started, head: head, note: extra || '' });
    };
    sock.setTimeout(timeoutMs);
    sock.on('connect', () => {
      sock.write(
        'GET /?k=' + key + ' HTTP/1.1\r\n' +
          'Host: ' + host + ':' + port + '\r\n' +
          'User-Agent: tunnel-hammer\r\n' +
          'Accept: text/html\r\n' +
          'Connection: close\r\n\r\n'
      );
    });
    sock.on('data', (chunk) => {
      bytes += chunk.length;
      if (!head) head = String(chunk.slice(0, 40)).split('\r\n')[0];
    });
    sock.on('timeout', () => done('TIMEOUT', 'no answer in ' + timeoutMs + 'ms'));
    sock.on('error', (e) => done('ERROR', e.code || e.message));
    sock.on('close', () => {
      if (bytes === 0) done('EMPTY', 'handshake ok, zero bytes back');
      else done('OK', bytes + ' bytes');
    });
  });
}

(async () => {
  console.log('hammer ' + who + '  ' + count + ' attempts');
  const tally = {};
  for (let i = 1; i <= count; i++) {
    const r = await once(i);
    tally[r.kind] = (tally[r.kind] || 0) + 1;
    console.log(
      '  #' + String(r.i).padStart(2) + ' ' + r.kind.padEnd(7) + ' ' +
        String(r.ms + 'ms').padStart(7) + '  ' + (r.head || r.note)
    );
  }
  const parts = Object.keys(tally).sort().map((k) => k + ' ' + tally[k] + '/' + count);
  console.log('tally: ' + parts.join(', '));
  console.log(
    tally.OK === count
      ? 'RESULT: OK - every attempt got a response from ' + who
      : 'RESULT: FLAKY - ' + (count - (tally.OK || 0)) + '/' + count +
        ' attempts did not get a response; a browser retries these, a one-shot WebView shows an error page'
  );
})();