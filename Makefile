.PHONY: up down build run reset shell record record-all parity parity-naive \
	deadcode chain-graph chain-graph-check \
	cutover-deadcode cutover-rollback-dryrun cutover-gate cutover-final-replay cutover-test

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

CUTOVER_CASES ?= default under_cap at_cap rate_change zero_amount non_transfer half_cent
CUTOVER_DAYS ?= 20
CUTOVER_ARGS ?=
CUTOVER_JAVA_IMAGE ?= maven:3.9-eclipse-temurin-21
CUTOVER_MVN_ARGS ?= -B -Dmaven.repo.local=/w/work/m2

cutover-deadcode:
	python3 tools/cutover/retire_with_chain.py

cutover-rollback-dryrun:
	docker compose exec -T estate python3 tools/cutover/rollback_dryrun.py

cutover-final-replay:
	@if ! $(MAKE) -n parity-java CASE=default >/dev/null 2>&1; then \
		echo "cutover-final-replay: no 'parity-java' target; the COG-1234 Java parity harness is not on this branch yet" >&2; \
		exit 2; \
	fi; \
	status=0; for c in $(CUTOVER_CASES); do \
		$(MAKE) --no-print-directory parity-java CASE=$$c || status=1; \
	done; \
	$(MAKE) --no-print-directory parity || status=1; \
	exit $$status

CUTOVER_JAVA = docker run --rm -u "$$(id -u):$$(id -g)" -e HOME=/tmp -e MAVEN_CONFIG=/tmp/.m2 \
	-v "$(CURDIR)":/w -w /w $(CUTOVER_JAVA_IMAGE)

cutover-test:
	$(CUTOVER_JAVA) mvn $(CUTOVER_MVN_ARGS) -f java/pom.xml -pl cutover -am verify

cutover-gate:
	$(CUTOVER_JAVA) sh -c 'mvn $(CUTOVER_MVN_ARGS) -q -f java/pom.xml -pl cutover -am package -DskipTests && \
		java -jar java/cutover/target/cutover.jar --required-days $(CUTOVER_DAYS) \
		--cases "$(strip $(CUTOVER_CASES))" --release-commit "$(shell git rev-parse HEAD)" $(CUTOVER_ARGS)'
