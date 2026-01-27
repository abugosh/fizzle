.PHONY: repl dev test clean help

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
	@echo "  make repl     - Start ClojureScript REPL (node)"
	@echo "  make dev      - Start browser dev server"
	@echo "  make test     - Run all tests"
	@echo "  make clean    - Remove build artifacts"
	@echo ""
	@echo "Validation (after linting added):"
	@echo "  make validate - Run all checks (lint + fmt + test)"

repl:
	npx shadow-cljs node-repl

dev:
	npx shadow-cljs watch app

test:
	npx shadow-cljs compile test && node out/test.js

clean:
	rm -rf out/ .shadow-cljs/ resources/public/js/
