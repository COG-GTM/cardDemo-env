.PHONY: up down build run reset shell record record-all parity parity-naive \
	parity-java java-build java-test deadcode chain-graph chain-graph-check

MVN ?= mvn
JAVA_PARITY_CASES := default under_cap at_cap rate_change zero_amount \
	non_transfer half_cent
# Datasets the Java port produces so far; widen as services land.
JAVA_PARITY_ONLY := AWS.M2.CARDDEMO.XFER.FEES

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
java-build:
	$(MVN) -B -q -f java/pom.xml -DskipTests package

java-test:
	$(MVN) -B -f java/pom.xml verify

parity-java: java-build
	@rc=0; for case in $(if $(CASE),$(CASE),$(JAVA_PARITY_CASES)); do \
		python3 tools/parity/java_candidate.py --case $$case \
			--out work/parity-java/$$case/candidate || exit $$?; \
		python3 tools/parity/compare.py --chain xferfee --case $$case \
			--candidate work/parity-java/$$case/candidate \
			--only $(JAVA_PARITY_ONLY) || rc=1; \
	done; exit $$rc

deadcode:
	python3 tools/deadcode/gen_smf.py
	python3 tools/deadcode/retire_split.py

chain-graph:
	python3 tools/chaingraph/gen_chain_graph.py

chain-graph-check:
	python3 tools/chaingraph/gen_chain_graph.py --check
