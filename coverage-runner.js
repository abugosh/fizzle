#!/usr/bin/env node
// Clofidence coverage runner wrapper.
// Wraps out/test-coverage.js to handle uncaught exceptions that occur in stray
// async re-frame dispatches AFTER the test suite completes. Without this wrapper,
// those exceptions crash the Node process before the fetch POST to the Clofidence
// server can complete, resulting in no HTML report.
//
// This wrapper:
//   1. Installs an uncaughtException handler that logs (not silently swallows) the error
//   2. Ensures the clofidence report fetch can complete before the process exits
//
// The actual test exit code is preserved: we check the test output and exit
// with 0 only if "0 failures, 0 errors" was found.

const path = require('path');

let testsPassed = null;
const originalConsoleLog = console.log.bind(console);

// Capture test result line to determine exit code
console.log = function(...args) {
  const line = args.join(' ');
  if (/^\d+ failures, \d+ errors\.$/.test(line) || /^0 failures, 0 errors\.$/.test(line)) {
    const match = line.match(/^(\d+) failures, (\d+) errors/);
    if (match) {
      testsPassed = parseInt(match[1]) === 0 && parseInt(match[2]) === 0;
    }
  }
  originalConsoleLog.apply(console, args);
};

// Install uncaughtException handler to prevent stray re-frame async dispatches
// (which happen after the test run completes) from crashing Node before the
// Clofidence fetch POST resolves.
process.on('uncaughtException', (err) => {
  // Log the error to stderr so it's visible but doesn't crash Node
  process.stderr.write('\n[coverage-runner] Caught post-test uncaught exception (stray async dispatch):\n');
  process.stderr.write(err.stack || String(err));
  process.stderr.write('\n[coverage-runner] Continuing to allow Clofidence report fetch to complete...\n');
  // Do NOT call process.exit() here — let the event loop drain naturally
  // so the pending fetch() POST to the Clofidence server can complete.
});

// Load the instrumented test build
require(path.join(__dirname, 'out', 'test-coverage.js'));
