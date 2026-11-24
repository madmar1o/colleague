// test_empty_answer_note.mjs - extracts emptyAnswerNote from chat.html and
// runs the canonical case set (empty-answer-cases.json): a finished answer
// with blank text must produce a visible note, not an empty bubble.
// Prints OK/FAIL per case and a summary. Exit 0 when there is no mismatch,
// 1 on mismatch, 2 on bad usage or a missing function.
import { readFileSync } from 'node:fs';

const htmlPath = process.argv[2];
const casesPath = process.argv[3];
if (!htmlPath || !casesPath) {
  console.error('usage: node test_empty_answer_note.mjs <chat.html> <empty-answer-cases.json>');
  process.exit(2);
}
const html = readFileSync(htmlPath, 'utf8');
const cases = JSON.parse(readFileSync(casesPath, 'utf8')).cases;

// Cut the function body as is: the anchor is a name-based substring, no
// product code is executed or rewritten here.
const start = html.indexOf('function emptyAnswerNote(');
if (start < 0) {
  console.error('FAIL: emptyAnswerNote not found in chat.html');
  process.exit(2);
}
const end = html.indexOf('\nfunction ', start + 1);
const source = html.slice(start, end > 0 ? end : undefined);
const emptyAnswerNote = new Function('return (' + source + ')')();

let mismatches = 0;
for (const c of cases) {
  const got = emptyAnswerNote(c.text, c.hadThinking);
  if (got !== c.note) {
    mismatches++;
    console.log('["FAIL"] case [' + JSON.stringify(c) + '] expected=['
      + c.note + '] got=[' + got + ']');
  } else {
    console.log('[  OK ] case [' + JSON.stringify(c.text) + ', '
      + c.hadThinking + ']');
  }
}
console.log('SUMMARY: ' + (cases.length - mismatches) + '/' + cases.length
  + ' passed, mismatches=' + mismatches);
process.exit(mismatches === 0 ? 0 : 1);
