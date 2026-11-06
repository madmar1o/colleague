// test_path_guard.mjs - extracts pathIsWorkspaceSafe from chat.html and runs
// the canonical case set (path-guard-cases.json), the same source that the
// Java WorkspacePathGuardTest reads. Prints OK/FAIL per case and a summary.
// Exit 0 when there is no mismatch, 1 on mismatch, 2 on bad usage.
import { readFileSync } from 'node:fs';

const htmlPath = process.argv[2];
const casesPath = process.argv[3];
if (!htmlPath || !casesPath) {
  console.error('usage: node test_path_guard.mjs <chat.html> <path-guard-cases.json>');
  process.exit(2);
}
const html = readFileSync(htmlPath, 'utf8');
const cases = JSON.parse(readFileSync(casesPath, 'utf8')).cases;

// Cut the function body as is: the anchor is a name-based substring, no
// product code is executed or rewritten here.
const start = html.indexOf('function pathIsWorkspaceSafe(p)');
if (start < 0) {
  console.error('FAIL: pathIsWorkspaceSafe not found in chat.html');
  process.exit(2);
}
const end = html.indexOf('\nfunction ', start + 1);
const source = html.slice(start, end > 0 ? end : undefined);
const pathIsWorkspaceSafe = new Function('return (' + source + ')')();

let mismatches = 0;
for (const c of cases) {
  const got = pathIsWorkspaceSafe(c.path);
  if (got !== c.safe) {
    mismatches++;
    console.log('["FAIL"] case [' + c.path + '] expected=' + c.safe + ' got=' + got);
  } else {
    console.log('[  OK ] case [' + c.path + '] = ' + got);
  }
}
console.log('SUMMARY: ' + (cases.length - mismatches) + '/' + cases.length
  + ' passed, mismatches=' + mismatches);
process.exit(mismatches === 0 ? 0 : 1);
