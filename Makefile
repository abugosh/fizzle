.PHONY: repl dev test clean help lint fmt-check fmt validate build-css

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
	@echo "  make lint      - Run clj-kondo linter"
	@echo "  make fmt-check - Check code formatting"
	@echo "  make fmt       - Auto-fix code formatting"
	@echo "  make validate  - Run lint + fmt-check + test"
	@echo "  make clean     - Remove build artifacts"

repl:
	npx shadow-cljs node-repl

dev:
	npx concurrently --kill-others \
	  "npx shadow-cljs watch app" \
	  "npx postcss src/css/app.css -o resources/public/css/app.css --watch"

test:
	npx shadow-cljs compile test && node out/test.js

build-css:
	mkdir -p resources/public/css
	npx postcss src/css/app.css -o resources/public/css/app.css

clean:
	rm -rf out/ .shadow-cljs/ resources/public/js/ resources/public/css/

lint:
	npx clj-kondo --lint src/

fmt-check:
	cljstyle check src/

fmt:
	cljstyle fix src/

validate:
	@$(MAKE) lint && $(MAKE) fmt-check && $(MAKE) test
