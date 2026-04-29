#!/usr/bin/env node
// Test for extract-coverage-baseline.js
// RED phase: run this BEFORE the extractor exists — it should fail.
// GREEN phase: run again after extractor is written — it should pass.

'use strict';

const path = require('path');
const fs = require('fs');
const { execSync } = require('child_process');

const root = path.join(__dirname, '..');
const extractorPath = path.join(__dirname, 'extract-coverage-baseline.js');
const baselinePath = path.join(root, 'coverage', 'baseline.edn');
const htmlDir = path.join(root, 'coverage', 'html');

let passed = 0;
let failed = 0;

function assert(condition, message) {
  if (condition) {
    console.log(`  PASS: ${message}`);
    passed++;
  } else {
    console.error(`  FAIL: ${message}`);
    failed++;
  }
}

function assertThrows(fn, message) {
  try {
    fn();
    console.error(`  FAIL: ${message} (expected to throw but did not)`);
    failed++;
  } catch (e) {
    console.log(`  PASS: ${message}`);
    passed++;
  }
}

// --- Test 1: extractor script exists ---
console.log('\nTest 1: extractor script exists');
assert(fs.existsSync(extractorPath), 'scripts/extract-coverage-baseline.js exists');

// --- Test 2: extractor runs without error on existing coverage/html/ ---
console.log('\nTest 2: extractor runs successfully on coverage/html/');
let extractorError = null;
try {
  execSync(`node "${extractorPath}"`, { cwd: root, stdio: 'pipe' });
} catch (e) {
  extractorError = e;
}
assert(extractorError === null, 'extractor exits 0');

// --- Test 3: baseline.edn is produced ---
console.log('\nTest 3: baseline.edn is produced');
assert(fs.existsSync(baselinePath), 'coverage/baseline.edn exists');

// --- Test 4: baseline.edn is non-empty ---
console.log('\nTest 4: baseline.edn is non-empty');
const baselineContent = fs.existsSync(baselinePath) ? fs.readFileSync(baselinePath, 'utf8') : '';
assert(baselineContent.length > 100, 'coverage/baseline.edn has content');

// --- Test 5: baseline.edn contains total-forms-hit ---
console.log('\nTest 5: baseline.edn has :total-forms-hit');
assert(baselineContent.includes(':total-forms-hit'), 'baseline has :total-forms-hit key');

// --- Test 6: baseline.edn contains total-sub-forms-hit ---
console.log('\nTest 6: baseline.edn has :total-sub-forms-hit');
assert(baselineContent.includes(':total-sub-forms-hit'), 'baseline has :total-sub-forms-hit key');

// --- Test 7: baseline.edn contains :namespaces key ---
console.log('\nTest 7: baseline.edn has :namespaces key');
assert(baselineContent.includes(':namespaces'), 'baseline has :namespaces key');

// --- Test 8: baseline.edn has at least 100 namespace entries ---
console.log('\nTest 8: baseline.edn has >=100 namespace entries');
const nsMatches = baselineContent.match(/:ns "/g) || [];
assert(nsMatches.length >= 100, `baseline has >=100 namespaces (got ${nsMatches.length})`);

// --- Test 9: namespaces are sorted alphabetically ---
console.log('\nTest 9: namespaces are sorted alphabetically');
const nsNames = [...baselineContent.matchAll(/:ns "([^"]+)"/g)].map(m => m[1]);
const sorted = [...nsNames].sort();
const isSorted = nsNames.every((ns, i) => ns === sorted[i]);
assert(isSorted, `namespaces are sorted alphabetically (first: ${nsNames[0]}, last: ${nsNames[nsNames.length - 1]})`);

// --- Test 10: total-forms-hit matches index.html ---
console.log('\nTest 10: total-forms-hit matches index.html');
const indexHtml = fs.readFileSync(path.join(htmlDir, 'index.html'), 'utf8');
const formMatch = indexHtml.match(/Total forms hit rate : (\d+)\/(\d+)/);
if (formMatch) {
  const expectedHit = parseInt(formMatch[1]);
  const expectedTotal = parseInt(formMatch[2]);
  const hitMatch = baselineContent.match(/:total-forms-hit (\d+)/);
  const totalMatch = baselineContent.match(/:total-forms (\d+)/);
  if (hitMatch && totalMatch) {
    assert(parseInt(hitMatch[1]) === expectedHit, `:total-forms-hit matches index.html (expected ${expectedHit})`);
    assert(parseInt(totalMatch[1]) === expectedTotal, `:total-forms matches index.html (expected ${expectedTotal})`);
  } else {
    assert(false, 'baseline contains :total-forms-hit and :total-forms values');
  }
} else {
  assert(false, 'index.html contains Total forms hit rate line');
}

// --- Test 11: total-sub-forms-hit matches index.html ---
console.log('\nTest 11: total-sub-forms-hit matches index.html');
const subFormMatch = indexHtml.match(/Total sub forms hit rate : (\d+)\/(\d+)/);
if (subFormMatch) {
  const expectedHit = parseInt(subFormMatch[1]);
  const expectedTotal = parseInt(subFormMatch[2]);
  const hitMatch = baselineContent.match(/:total-sub-forms-hit (\d+)/);
  const totalMatch = baselineContent.match(/:total-sub-forms (\d+)/);
  if (hitMatch && totalMatch) {
    assert(parseInt(hitMatch[1]) === expectedHit, `:total-sub-forms-hit matches index.html (expected ${expectedHit})`);
    assert(parseInt(totalMatch[1]) === expectedTotal, `:total-sub-forms matches index.html (expected ${expectedTotal})`);
  } else {
    assert(false, 'baseline contains :total-sub-forms-hit and :total-sub-forms values');
  }
} else {
  assert(false, 'index.html contains Total sub forms hit rate line');
}

// --- Test 12: error handling — missing coverage/html/ exits 1 ---
console.log('\nTest 12: extractor exits 1 when coverage/html/ is missing');
const tmpDir = path.join(root, 'coverage-tmp-test-dir');
let exitCode = 0;
try {
  execSync(`node "${extractorPath}"`, {
    cwd: root,
    stdio: 'pipe',
    env: { ...process.env, COVERAGE_HTML_DIR: tmpDir }
  });
} catch (e) {
  exitCode = e.status;
}
assert(exitCode === 1, 'extractor exits 1 when coverage/html/ is missing (via env override)');

// --- Summary ---
console.log(`\n${'='.repeat(50)}`);
console.log(`Results: ${passed} passed, ${failed} failed`);
if (failed > 0) {
  process.exit(1);
} else {
  console.log('All tests passed!');
  process.exit(0);
}
