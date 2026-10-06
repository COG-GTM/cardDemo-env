.PHONY: up down build run reset shell record record-all parity parity-naive \
	parity-java parity-console-build parity-console \
	deadcode chain-graph chain-graph-check

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

# --- parity-console (COG-1250): Java candidate + live console -------------------
JAVA_HOME ?= /usr/lib/jvm/java-21-openjdk-amd64
PARITY_JAR := parity-console/target/parity-console.jar
JAVA_CASES ?= default half_cent rate_change at_cap under_cap zero_amount non_transfer

parity-console-build:
	JAVA_HOME=$(JAVA_HOME) mvn -q -f parity-console/pom.xml package -DskipTests

parity-java: parity-console-build
	@set -e; rc=0; for c in $(if $(CASE),$(CASE),$(JAVA_CASES)); do \
		$(JAVA_HOME)/bin/java -jar $(PARITY_JAR) --candidate --case $$c \
			--out work/parity/$$c/java $(if $(BREAK),--break-it,); \
		docker compose exec -T estate python3 tools/parity/compare.py \
			--chain xferfee --case $$c --candidate work/parity/$$c/java \
			--report work/parity/$$c/java-report.md || rc=1; \
	done; exit $$rc

parity-console: parity-console-build
	$(JAVA_HOME)/bin/java -jar $(PARITY_JAR)
