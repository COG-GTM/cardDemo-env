.PHONY: up down build run reset shell record record-all parity parity-naive \
	deadcode chain-graph chain-graph-check java-package java-test parity-java

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

JAVA_CASES ?= default under_cap at_cap rate_change zero_amount non_transfer half_cent

java-package:
	cd java && mvn -B -q -DskipTests package

java-test:
	cd java && mvn -B verify

parity-java: java-package
	@rc=0; for case in $(if $(CASE),$(CASE),$(JAVA_CASES)); do \
		python3 tools/parity/java_candidate.py --case $$case \
			$(if $(ONLY),--only $(ONLY)) || rc=1; \
	done; exit $$rc
