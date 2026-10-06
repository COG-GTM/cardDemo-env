.PHONY: up down build run reset shell record record-all parity parity-naive \
	java-build java-test parity-java deadcode chain-graph chain-graph-check

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

JAVA_CASES ?= default under_cap at_cap rate_change zero_amount non_transfer half_cent
MVN ?= mvn -B -q

java-build:
	cd java && $(MVN) -DskipTests package

java-test:
	cd java && $(MVN) verify

# Java candidate per case under work/parity-java/<case>/ (out/, candidate/, report.md).
# fee_schedule_check.py is the fee-schedule-service (COG-1236) module gate; compare.py is
# the full-chain gate and stays red until every stage is ported.
parity-java: java-build
	@status=0; for case in $(if $(CASE),$(CASE),$(JAVA_CASES)); do \
		python3 tools/parity/java_candidate.py --case $$case \
			--out work/parity-java/$$case || exit 1; \
		python3 tools/parity/fee_schedule_check.py --case $$case \
			--candidate work/parity-java/$$case/out || status=1; \
		python3 tools/parity/compare.py --chain xferfee --case $$case \
			--candidate work/parity-java/$$case/candidate || status=1; \
	done; exit $$status

deadcode:
	python3 tools/deadcode/gen_smf.py
	python3 tools/deadcode/retire_split.py

chain-graph:
	python3 tools/chaingraph/gen_chain_graph.py

chain-graph-check:
	python3 tools/chaingraph/gen_chain_graph.py --check
