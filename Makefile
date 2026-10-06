.PHONY: up down build run reset shell record record-all parity parity-naive parity-java java-test \
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

PARITY_CASES ?= default under_cap at_cap rate_change zero_amount non_transfer half_cent
JAVA_HOME ?= $(firstword $(wildcard /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/temurin-21*))
export JAVA_HOME

parity-java:
	mvn -q -B -f java/pom.xml -DskipTests package
	@rc=0; for case in $(or $(CASE),$(PARITY_CASES)); do \
		work=work/parity-java/$$case; \
		python3 tools/parity/java_candidate.py --case $$case --out $$work $(if $(NO_STUB),--no-stub-upstream) || exit 1; \
		python3 tools/parity/compare.py --chain xferfee --case $$case \
			--candidate $$work/candidate --report $$work/report.md || rc=1; \
		$(if $(ONLY),grep -F "$(ONLY)" $$work/report.md || echo "$$case: $(ONLY) 0 diffs";) \
	done; \
	$(if $(STEP),python3 tools/parity/step_scope.py --step $(STEP) \
		$(foreach c,$(or $(CASE),$(PARITY_CASES)),work/parity-java/$(c)/report.md) && exit 0;) \
	exit $$rc

java-test:
	mvn -B -f java/pom.xml verify
