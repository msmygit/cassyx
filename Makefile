# =============================================================================
# cassyx — the only entry point anyone needs to learn (plan §2.2).
#
#   make up      builds and starts the full stack, waits for health, opens the UI
#   make down    stop and clean volumes
#   make dev     hot reload: Spring DevTools + Vite HMR against the same Cassandra
#   make test    unit + integration (Testcontainers) + frontend, with coverage gates
#   make e2e     Playwright against a freshly seeded stack, headless
#   make e2e-ui  same, headed, for debugging
#   make bench   the §11.2 performance benchmarks
#   make verify  everything CI runs, locally — the pre-push gate
#                (contract · lint · arch · unit · integration · e2e · security)
#   make seed    reload demo data (incl. a vector table for ANN)
#
# HARD REQUIREMENT: this works on a clean checkout with only Docker and Make.
# No local Java, Node or Maven — every build and test runs inside a container.
#
# Windows/WSL parity: ./cassyx <target> does the same thing without GNU Make.
# =============================================================================

SHELL := /bin/bash
.DEFAULT_GOAL := help
.SHELLFLAGS := -eu -o pipefail -c

ROOT        := $(shell cd "$(dir $(lastword $(MAKEFILE_LIST)))" && pwd)
COMPOSE     := docker compose -f $(ROOT)/docker-compose.yml
DC_APP      := $(COMPOSE) --profile app
DC_DEV      := $(COMPOSE) --profile dev
DC_TOOLS    := $(COMPOSE) --profile tools
DC_E2E      := $(COMPOSE) --profile e2e
PREFLIGHT   := bash $(ROOT)/scripts/preflight.sh
WAIT        := bash $(ROOT)/scripts/wait-for-health.sh

# Read ports from .env if present, else fall back to the documented defaults.
WEB_PORT    := $(shell . $(ROOT)/.env 2>/dev/null; echo $${CASSYX_WEB_PORT:-8080})
VITE_PORT   := $(shell . $(ROOT)/.env 2>/dev/null; echo $${CASSYX_VITE_PORT:-5173})
APP_URL     := http://localhost:$(WEB_PORT)
DEV_URL     := http://localhost:$(VITE_PORT)

# Containerised toolchain shorthands.
MVN         := $(DC_TOOLS) run --rm --no-deps maven
NPM         := $(DC_TOOLS) run --rm --no-deps node

CYAN := \033[36m
BOLD := \033[1m
DIM  := \033[2m
OFF  := \033[0m
say   = @printf "$(CYAN)▸$(OFF) $(BOLD)%s$(OFF)\n" "$(1)"

.PHONY: help up down dev test e2e e2e-ui bench verify seed smoke \
        db cql logs ps config show-contracts clean nuke restart open \
        contract lint arch unit integration security mutation compat \
        lint-backend lint-frontend unit-backend unit-frontend \
        build-images check-backend check-frontend check-e2e \
        check-backend-src check-frontend-src

# -----------------------------------------------------------------------------
help: ## Show this help
	@printf "\n$(BOLD)cassyx$(OFF) — one-command developer experience (plan §2.2)\n\n"
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  $(CYAN)%-14s$(OFF) %s\n", $$1, $$2}'
	@printf "\n$(DIM)Only Docker and Make are required. Everything else runs in containers.$(OFF)\n\n"

# =============================================================================
# THE one command
# =============================================================================
up: ## Build + start the full stack, wait for health, open the UI
	$(call say,preflight)
	@$(PREFLIGHT) docker env backend frontend
	$(call say,starting Cassandra 5.x — real health check (never a sleep))
	@$(COMPOSE) up -d cassandra
	@$(WAIT) cassandra 300
	$(call say,seeding demo data)
	@$(MAKE) --no-print-directory seed
	$(call say,building and starting backend + frontend)
	@$(DC_APP) up -d --build backend frontend
	@$(WAIT) backend 300
	@$(WAIT) frontend 120
	@bash $(ROOT)/scripts/open-url.sh $(APP_URL)

down: ## Stop everything and remove volumes (full reset)
	$(call say,stopping and removing volumes)
	@$(COMPOSE) --profile app --profile dev --profile tools --profile e2e --profile seed \
	    down --volumes --remove-orphans

restart: down up ## down + up

open: ## Open the app URL in a browser
	@bash $(ROOT)/scripts/open-url.sh $(APP_URL)

# =============================================================================
# Hot reload
# =============================================================================
dev: ## Hot-reload mode: Spring DevTools + Vite HMR against the same Cassandra
	$(call say,preflight)
	@$(PREFLIGHT) docker env backend frontend
	$(call say,starting Cassandra 5.x)
	@$(COMPOSE) up -d cassandra
	@$(WAIT) cassandra 300
	@$(MAKE) --no-print-directory seed
	$(call say,starting backend-dev (DevTools) + frontend-dev (Vite HMR))
	@$(DC_DEV) up -d backend-dev frontend-dev
	@printf "\n  backend  (DevTools restart on rebuild) http://localhost:$(shell . $(ROOT)/.env 2>/dev/null; echo $${CASSYX_API_PORT:-8081})/api/health\n"
	@printf "  frontend (Vite HMR)                    $(DEV_URL)\n\n"
	@printf "  $(DIM)follow logs: make logs$(OFF)\n\n"
	@bash $(ROOT)/scripts/open-url.sh $(DEV_URL)

# =============================================================================
# Database / seed  — these work with NO backend or frontend present
# =============================================================================
db: ## Start only Cassandra 5.x and wait until it is healthy
	@$(PREFLIGHT) docker env
	@$(COMPOSE) up -d cassandra
	@$(WAIT) cassandra 300

seed: db ## Reload demo data (collections, UDTs, counters, vectors+SAI, skew, wide table)
	$(call say,seeding demo keyspace — override volume with 'make seed SEED_SCALE=50')
	@$(COMPOSE) --profile seed run --rm $(if $(SEED_SCALE),-e SEED_SCALE=$(SEED_SCALE),) seed

cql: db ## Open an interactive cqlsh shell against the dev cluster
	@$(COMPOSE) exec cassandra cqlsh

# =============================================================================
# Tests — all containerised
# =============================================================================
test: unit integration unit-frontend ## Unit + integration (Testcontainers) + frontend, with coverage gates

unit: unit-backend ## Backend unit tests (no containers, no network)

unit-backend: check-backend-src
	$(call say,mvn test — unit (Java 21))
	@$(MVN) -B -ntp test

unit-frontend: check-frontend-src
	$(call say,vitest --coverage — 70% statements gate (§11.1))
	@$(NPM) "npm ci --no-audit --no-fund || npm install --no-audit --no-fund; npm run test"

integration: check-backend-src ## mvn verify — Testcontainers Cassandra 5.x + JaCoCo gates
	$(call say,mvn verify (Testcontainers Cassandra 5.x + JaCoCo coverage gates))
	@$(DC_TOOLS) run --rm --no-deps maven -B -ntp verify

arch: check-backend-src ## ArchUnit: no Spring below cassyx-api, no cross-module impl imports (§2.1)
	$(call say,ArchUnit rules (§2.1 modularity contract))
	@if [ -z "$$(find $(ROOT)/backend \( -name '*ArchTest.java' -o -name '*ArchitectureTest.java' \) -print -quit 2>/dev/null)" ]; then \
	   printf "\033[31m✗ no *ArchTest found under backend/.\033[0m\n"; \
	   printf "  §2.1 requires ArchUnit rules that fail the build on:\n"; \
	   printf "    · any org.springframework import below cassyx-api\n"; \
	   printf "    · any module depending on a sibling's implementation package\n"; \
	   printf "  Name them *ArchTest.java or *ArchitectureTest.java, e.g.\n"; \
	   printf "    backend/cassyx-api/src/test/java/.../ModularityArchitectureTest.java\n"; \
	   exit 1; \
	 fi
	@$(MVN) -B -ntp test -Dtest='*ArchTest,*ArchitectureTest' -DfailIfNoTests=false \
	    -Dsurefire.failIfNoSpecifiedTests=false

contract: ## API contract gate — redocly lint (0 errors/0 warnings) · $$refs resolve · gen:api (§2.3)
	$(call say,API contract gate — openapi/cassyx-api.yaml (§2.3))
	@bash $(ROOT)/scripts/check-openapi.sh

lint: lint-backend lint-frontend ## spotless/checkstyle · eslint · tsc --noEmit

lint-backend: check-backend-src
	$(call say,spotless:check + checkstyle)
	@$(MVN) -B -ntp spotless:check checkstyle:check

lint-frontend: check-frontend-src
	$(call say,eslint + tsc --noEmit)
	@$(NPM) "npm ci --no-audit --no-fund || npm install --no-audit --no-fund; npm run lint && npm run typecheck"

mutation: check-backend-src ## PIT mutation testing on cassyx-core + cassyx-bulk (nightly; 70% gate)
	$(call say,PIT mutation testing — core + bulk only (§11.1))
	@$(DC_TOOLS) run --rm --no-deps maven -B -ntp -pl cassyx-core,cassyx-bulk \
	    org.pitest:pitest-maven:mutationCoverage

security: ## OWASP Dependency-Check · gitleaks · npm audit
	$(call say,gitleaks (secret scan))
	@docker run --rm -v "$(ROOT):/repo:ro" zricethezav/gitleaks:latest \
	    detect --source=/repo --no-git --redact --config=/repo/.gitleaks.toml || \
	    (printf "\033[31mgitleaks found candidate secrets — do not push.\033[0m\n"; exit 1)
	@if [ -f "$(ROOT)/backend/pom.xml" ]; then \
	   printf "$(CYAN)▸$(OFF) OWASP Dependency-Check (CVE-2026-24400 / CVE-2023-6378 pins, §2)\n"; \
	   if [ -z "$${NVD_API_KEY:-}" ]; then \
	     printf "\033[33m!\033[0m NVD_API_KEY is not set — SKIPPING OWASP Dependency-Check.\n"; \
	     printf "  Without a key the NVD API rejects the update outright:\n"; \
	     printf "    NvdApiException: Invalid API Key, length of 0 too short\n"; \
	     printf "  Running it anyway would fail every build while producing no security signal,\n"; \
	     printf "  so it is skipped. gitleaks and npm audit below still run.\n"; \
	     printf "  Get a free key (~1 min): https://nvd.nist.gov/developers/request-an-api-key\n"; \
	     printf "    local: export NVD_API_KEY=...\n"; \
	     printf "    CI:    add it as the NVD_API_KEY repo secret (Settings > Secrets > Actions)\n"; \
	     if [ -n "$${SECURITY_STRICT:-}" ]; then \
	       printf "\033[31mSECURITY_STRICT is set — refusing to skip. Provide NVD_API_KEY.\033[0m\n"; exit 1; \
	     fi; \
	   else \
	     $(DC_TOOLS) run --rm --no-deps -e NVD_API_KEY maven -B -ntp \
	       org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7 \
	       -DnvdApiKey=$$NVD_API_KEY || exit 1; \
	   fi; \
	 else printf "\033[33m! backend/ absent — OWASP Dependency-Check skipped\033[0m\n"; fi
	@if [ -f "$(ROOT)/frontend/package.json" ]; then \
	   printf "$(CYAN)▸$(OFF) npm audit\n"; \
	   $(NPM) "npm ci --no-audit --no-fund || npm install --no-audit --no-fund; npm audit --audit-level=high" || exit 1; \
	 else printf "\033[33m! frontend/ absent — npm audit skipped\033[0m\n"; fi

compat: ## Nightly compatibility smoke across the §7.1 target matrix
	$(call say,compatibility matrix — run one target at a time via CASSANDRA_IMAGE)
	@bash $(ROOT)/scripts/compat.sh

# =============================================================================
# E2E
# =============================================================================
smoke: ## Fast integration gate: the stack actually boots and the ungated endpoints answer
	@$(PREFLIGHT) docker env backend frontend
	@CASSYX_NO_OPEN=1 $(MAKE) --no-print-directory up
	@bash $(ROOT)/scripts/smoke.sh

e2e: ## Playwright against a freshly seeded stack, headless
	@$(PREFLIGHT) docker env backend frontend e2e
	@CASSYX_NO_OPEN=1 $(MAKE) --no-print-directory up
	$(call say,Playwright (trace + video retained on failure))
	@$(DC_E2E) run --rm e2e

e2e-ui: ## Same as e2e but headed, for debugging (uses your local browser via --ui-host)
	@$(PREFLIGHT) docker env backend frontend e2e
	@CASSYX_NO_OPEN=1 $(MAKE) --no-print-directory up
	$(call say,Playwright UI mode — open the printed URL)
	@$(DC_E2E) run --rm --service-ports e2e \
	    "npm ci --no-audit --no-fund || npm install --no-audit --no-fund; \
	     npx playwright test --ui --ui-host=0.0.0.0 --ui-port=8130"

# =============================================================================
# Bench
# =============================================================================
bench: ## The §11.2 performance benchmarks; appends to bench/trend.csv
	@$(PREFLIGHT) docker env
	@bash $(ROOT)/scripts/bench.sh

# =============================================================================
# The pre-push gate — IDENTICAL to the per-PR CI job set (§11.1).
# CI invokes exactly these targets, so local and CI cannot drift.
# =============================================================================
verify: ## Everything CI runs per PR: contract · lint · arch · unit · integration · e2e · security
	@$(MAKE) --no-print-directory contract
	@$(MAKE) --no-print-directory lint
	@$(MAKE) --no-print-directory arch
	@$(MAKE) --no-print-directory unit
	@$(MAKE) --no-print-directory unit-frontend
	@$(MAKE) --no-print-directory integration
	@$(MAKE) --no-print-directory smoke
	@$(MAKE) --no-print-directory e2e
	@$(MAKE) --no-print-directory security
	@printf "\n\033[32m✓ verify passed — same job set branch protection requires.\033[0m\n\n"

# =============================================================================
# Introspection / housekeeping
# =============================================================================
config: ## Validate docker-compose.yml (all profiles)
	@$(PREFLIGHT) docker env
	@$(COMPOSE) --profile app --profile dev --profile tools --profile e2e --profile seed config >/dev/null
	@printf "\033[32m✓ docker-compose.yml is valid\033[0m\n"

ps: ## Show stack status
	@$(COMPOSE) --profile app --profile dev ps

logs: ## Follow logs for all running services
	@$(COMPOSE) --profile app --profile dev logs -f --tail=100

clean: ## Stop containers but KEEP volumes (data survives)
	@$(COMPOSE) --profile app --profile dev --profile tools --profile e2e --profile seed down --remove-orphans

nuke: down ## down + prune dangling cassyx images
	@docker image prune -f --filter label=com.docker.compose.project=$${COMPOSE_PROJECT_NAME:-cassyx} || true

show-contracts: ## Print the build contract other workstreams must satisfy
	@awk '/^## Build contracts/{f=1} f && /^## / && !/^## Build contracts/{exit} f' $(ROOT)/README.md

# Graceful degradation while backend/ and frontend/ are still being written.
# Image-level checks (needed to run the stack)
check-backend:
	@$(PREFLIGHT) docker env backend
check-frontend:
	@$(PREFLIGHT) docker env frontend
check-e2e:
	@$(PREFLIGHT) docker env e2e
# Source-level checks (needed to build/test, no Dockerfile required)
check-backend-src:
	@$(PREFLIGHT) docker env backend-src
check-frontend-src:
	@$(PREFLIGHT) docker env frontend-src
