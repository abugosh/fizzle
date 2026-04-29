.PHONY: repl dev test coverage clean help lint fmt-check fmt validate build-css lint-test-paths lint-pending-selection release arch

# Detect Java - try common locations
JAVA_HOME ?= $(shell \
  if [ -d "/usr/local/opt/openjdk" ]; then echo "/usr/local/opt/openjdk"; \
  elif [ -d "/opt/homebrew/opt/openjdk" ]; then echo "/opt/homebrew/opt/openjdk"; \
  elif [ -n "$$JAVA_HOME" ]; then echo "$$JAVA_HOME"; \
  else echo ""; fi)

ifneq ($(JAVA_HOME),)
  export PATH := $(JAVA_HOME)/bin:$(PATH)
endif

help:
	@echo "Fizzle Development Commands"
	@echo ""
	@echo "  make repl      - Start ClojureScript REPL (node)"
	@echo "  make dev       - Start browser dev server + Tailwind watcher"
	@echo "  make test      - Run all tests"
	@echo "  make coverage  - Run tests with Clofidence coverage instrumentation"
	@echo "  make lint      - Run clj-kondo linter"
	@echo "  make fmt-check - Check code formatting"
	@echo "  make fmt       - Auto-fix code formatting"
	@echo "  make validate  - Run lint + fmt-check + test"
	@echo "  make arch      - Start LikeC4 architecture diagram server"
	@echo "  make clean     - Remove build artifacts"

repl:
	npx shadow-cljs node-repl

dev:
	npx concurrently --kill-others \
	  "npx shadow-cljs watch app" \
	  "npx postcss src/css/app.css -o resources/public/css/app.css --watch"

test:
	npx shadow-cljs compile test && node out/test.js

# Coverage: compile with Clofidence instrumentation and run the full suite.
# Requires: clj (Clojure CLI) and Java installed. Run: brew install clojure/tools/clojure
# Output: coverage/html/ (gitignored HTML report), coverage/baseline.edn (committed baseline).
# Do NOT use for regular test runs — instrumentation adds overhead.
# Architecture: server runs in background on port 7799; compilation uses flow-storm CLJS fork;
# node process POSTs trace data to server; server writes HTML report and exits.
# Note: coverage-runner.js wraps out/test-coverage.js to handle stray post-test async
# re-frame dispatches (uncaught exceptions) that would otherwise crash Node before the
# Clofidence fetch POST can complete.
coverage:
	@mkdir -p coverage/html
	@echo "Starting Clofidence report server on port 7799 (background)..."
	@clj -X:clofidence-server & \
	  CLJ_PID=$$!; \
	  echo "Waiting for Clofidence server (pid $$CLJ_PID)..."; \
	  until nc -z localhost 7799 2>/dev/null; do sleep 1; done; \
	  echo "Clofidence server ready. Compiling :test-coverage with flow-storm CLJS fork..."; \
	  clj -A:clofidence-compile -M -m shadow.cljs.devtools.cli compile test-coverage; \
	  echo "Running instrumented tests (8GB heap)..."; \
	  node --max-old-space-size=8192 coverage-runner.js; \
	  wait $$CLJ_PID || true; \
	  echo "Extracting coverage/baseline.edn..."; \
	  node scripts/extract-coverage-baseline.js; \
	  echo "Coverage report written to coverage/html/"; \
	  echo "Baseline written to coverage/baseline.edn"

build-css:
	mkdir -p resources/public/css
	npx postcss src/css/app.css -o resources/public/css/app.css

release: clean build-css
	npx shadow-cljs release app

clean:
	rm -rf out/ .shadow-cljs/ resources/public/js/ resources/public/css/

lint:
	npx clj-kondo --lint src/

fmt-check:
	cljstyle check src/

fmt:
	cljstyle fix src/

lint-test-paths:
	@echo "Checking card tests for production path bypasses..."
	@grep -rn 'confirm-cast-time-target\|execute-confirmed-selection\|execute-effect\|execute-peek-selection\|build-tutor-selection\|execute-tutor-selection' \
		src/test/fizzle/cards/ \
		--include="*_test.cljs" \
		| grep -v '^\s*;;' \
		|| echo "  No production path bypasses found."
	@echo "Review matches above — happy-path tests should use th/ helpers instead."
	@echo "Edge case tests calling internals directly are acceptable."

lint-pending-selection:
	@echo "Checking for direct assoc :game/pending-selection outside spec.cljs..."
	@! grep -rn '(assoc[[:space:]][^i].*:game/pending-selection\|(assoc :game/pending-selection' src/main/ --include="*.cljs" \
		| grep -v 'selection/spec.cljs' \
		| grep -v 'assoc-in' \
		| grep -q . \
		&& echo "  No direct pending-selection assocs found outside spec.cljs." \
		|| (grep -rn '(assoc[[:space:]][^i].*:game/pending-selection\|(assoc :game/pending-selection' src/main/ --include="*.cljs" | grep -v 'selection/spec.cljs' | grep -v 'assoc-in'; exit 1)

arch:
	npx likec4 start docs/arch/

validate:
	@$(MAKE) lint && $(MAKE) fmt-check && $(MAKE) lint-pending-selection && $(MAKE) test
