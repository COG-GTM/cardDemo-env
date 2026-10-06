.PHONY: up down build run reset shell record record-all parity parity-naive \
	deadcode chain-graph chain-graph-check java-build java-up java-down parity-java

MVN ?= mvn
JAVA_COMPOSE = docker compose --profile java
JAVA_SERVICES = fee-schedule-service transfer-intake-service \
	account-posting-service outbox-relay reconciliation-service
PARITY_CASES ?= $(sort $(notdir $(patsubst %/case.json,%,$(wildcard fixtures/xferfee/*/case.json))))

up:
	docker compose up -d --build --wait

down:
	docker compose down

build:
	docker compose exec -T estate tools/build.sh

run:
	docker compose exec -T estate python3 tools/runjcl/runjcl.py --chain $(CHAIN) --load-fixtures default --db-reset

reset:
	-docker compose exec -T estate sh -c 'rm -rf /estate/datasets /estate/work /estate/loadlib'
	rm -rf datasets work loadlib
	docker compose down -v

shell:
	docker compose exec -T estate bash

record:
	docker compose exec -T estate python3 tools/parity/recorder.py \
		--chain xferfee --case $(CASE)

record-all:
	docker compose exec -T estate python3 tools/parity/recorder.py \
		--chain xferfee --all

parity:
	docker compose exec -T estate sh -c \
		'if [ -n "$(CASE)" ]; then \
			python3 tools/parity/compare.py --chain xferfee \
			--case "$(CASE)"; \
		else \
			python3 tools/parity/compare.py --chain xferfee --all \
			--report work/parity/report.md; \
		fi'

parity-naive:
	docker compose exec -T estate sh -c \
		'python3 tools/parity/naive_ref.py --case $(CASE) \
		--out work/parity/$(CASE)/naive && \
		python3 tools/parity/compare.py --chain xferfee --case $(CASE) \
		--candidate work/parity/$(CASE)/naive \
		--report work/parity/$(CASE)/naive-report.md'

deadcode:
	python3 tools/deadcode/gen_smf.py
	python3 tools/deadcode/retire_split.py

chain-graph:
	python3 tools/chaingraph/gen_chain_graph.py

chain-graph-check:
	python3 tools/chaingraph/gen_chain_graph.py --check

java-build:
	cd java && $(MVN) -B -q package

java-up: java-build
	$(JAVA_COMPOSE) build $(JAVA_SERVICES) parity-replay
	$(JAVA_COMPOSE) up -d --wait db kafka $(JAVA_SERVICES)

java-down:
	$(JAVA_COMPOSE) stop kafka $(JAVA_SERVICES)

parity-java:
	@cases="$(CASE)"; [ -n "$$cases" ] || cases="$(PARITY_CASES)"; \
	status=0; \
	for c in $$cases; do \
		out=work/parity-java/$$c; \
		$(JAVA_COMPOSE) run --rm -T parity-replay --mode=events \
			--case $$c --out $$out/candidate || status=1; \
		python3 tools/parity/compare.py --chain xferfee --case $$c \
			--candidate $$out/candidate --report $$out/report.md || status=1; \
	done; \
	python3 tools/parity/java_summary.py $$cases; \
	exit $$status
