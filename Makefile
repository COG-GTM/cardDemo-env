.PHONY: up down build run reset shell record record-all parity parity-naive \
	parity-java parity-java-recon deadcode chain-graph chain-graph-check

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

JAVA_CASE = $(or $(CASE),default)
MVN = mvn -B -q -Dmaven.repo.local=/estate/work/m2

parity-java:
	docker compose exec -T estate sh -c \
		'$(MVN) -f java/pom.xml package && \
		python3 tools/parity/java_candidate.py --case $(JAVA_CASE) \
		--out work/parity/$(JAVA_CASE)/java && \
		python3 tools/parity/compare.py --chain xferfee --case $(JAVA_CASE) \
		--candidate work/parity/$(JAVA_CASE)/java/candidate \
		--report work/parity/$(JAVA_CASE)/java-report.md'

RECON_CASES = default under_cap at_cap rate_change zero_amount non_transfer half_cent

parity-java-recon:
	docker compose exec -T estate sh -c \
		'$(MVN) -f java/pom.xml package && fail=0 && \
		for c in $(or $(CASE),$(RECON_CASES)); do \
		python3 tools/parity/java_recon_stage.py --case $$c \
		--out work/parity/$$c/java-recon || exit 1; \
		python3 tools/parity/compare.py --chain xferfee --case $$c \
		--candidate work/parity/$$c/java-recon/candidate \
		--report work/parity/$$c/java-recon-report.md > /dev/null || fail=1; \
		echo "$$c: $$(tail -1 work/parity/$$c/java-recon-report.md)"; \
		done; exit $$fail'

deadcode:
	python3 tools/deadcode/gen_smf.py
	python3 tools/deadcode/retire_split.py

chain-graph:
	python3 tools/chaingraph/gen_chain_graph.py

chain-graph-check:
	python3 tools/chaingraph/gen_chain_graph.py --check
