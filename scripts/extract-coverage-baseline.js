#!/usr/bin/env node
// extract-coverage-baseline.js
// Parses Clofidence HTML report from coverage/html/ and writes coverage/baseline.edn.
// No npm dependencies — uses only Node.js built-in fs + regex.
//
// Usage:   node scripts/extract-coverage-baseline.js
// Output:  coverage/baseline.edn (committed, diff-friendly)
//
// COVERAGE_HTML_DIR env var overrides input dir (for testing).

'use strict';

const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const htmlDir = process.env.COVERAGE_HTML_DIR || path.join(root, 'coverage', 'html');
const outputPath = path.join(root, 'coverage', 'baseline.edn');

// --- Parse index.html for totals ---
const indexPath = path.join(htmlDir, 'index.html');
if (!fs.existsSync(indexPath)) {
  process.stderr.write(
    `Error: ${indexPath} does not exist.\n` +
    `Run 'make coverage' first to generate the HTML report.\n`
  );
  process.exit(1);
}

const indexHtml = fs.readFileSync(indexPath, 'utf8');

const formsMatch = indexHtml.match(/Total forms hit rate : (\d+)\/(\d+)/);
if (!formsMatch) {
  process.stderr.write(`Error: Could not parse 'Total forms hit rate' from ${indexPath}\n`);
  process.exit(1);
}
const totalFormsHit = parseInt(formsMatch[1], 10);
const totalForms = parseInt(formsMatch[2], 10);

const subFormsMatch = indexHtml.match(/Total sub forms hit rate : (\d+)\/(\d+)/);
if (!subFormsMatch) {
  process.stderr.write(`Error: Could not parse 'Total sub forms hit rate' from ${indexPath}\n`);
  process.exit(1);
}
const totalSubFormsHit = parseInt(subFormsMatch[1], 10);
const totalSubForms = parseInt(subFormsMatch[2], 10);

// --- Parse per-namespace HTML files ---
const htmlFiles = fs.readdirSync(htmlDir)
  .filter(f => f.endsWith('.html') && f !== 'index.html');

const namespaces = [];

for (const file of htmlFiles) {
  const filePath = path.join(htmlDir, file);
  const content = fs.readFileSync(filePath, 'utf8');

  // First <b> line has format: namespace-name hit/total (pct%)
  // Example: <div><b>fizzle.bots.action-spec 12/11 (109.1%)</b>
  const bMatch = content.match(/<b>([^\s]+)\s+(\d+)\/(\d+)\s+\([^)]+\)<\/b>/);
  if (!bMatch) {
    process.stderr.write(`Warning: Could not parse <b> line in ${file}, skipping.\n`);
    continue;
  }

  const ns = bMatch[1];
  const subFormsHit = parseInt(bMatch[2], 10);
  const subForms = parseInt(bMatch[3], 10);

  namespaces.push({ ns, subFormsHit, subForms });
}

// Sort alphabetically by namespace name — eliminates filesystem directory order non-determinism
namespaces.sort((a, b) => a.ns < b.ns ? -1 : a.ns > b.ns ? 1 : 0);

// --- Write coverage/baseline.edn ---
// EDN format: no timestamps, no run-IDs, sorted by :ns
const nsEntries = namespaces
  .map(({ ns, subFormsHit, subForms }) =>
    ` {:ns "${ns}" :sub-forms-hit ${subFormsHit} :sub-forms ${subForms}}`
  )
  .join('\n');

const edn =
  `{:total-forms-hit ${totalFormsHit}\n` +
  ` :total-forms ${totalForms}\n` +
  ` :total-sub-forms-hit ${totalSubFormsHit}\n` +
  ` :total-sub-forms ${totalSubForms}\n` +
  ` :namespaces\n` +
  ` [${nsEntries.trimStart()}\n` +
  ` ]}\n`;

// Ensure coverage/ directory exists
const coverageDir = path.join(root, 'coverage');
if (!fs.existsSync(coverageDir)) {
  fs.mkdirSync(coverageDir, { recursive: true });
}

fs.writeFileSync(outputPath, edn, 'utf8');

console.log(`Baseline written to coverage/baseline.edn (${namespaces.length} namespaces)`);
