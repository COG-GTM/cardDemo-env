.PHONY: up down build run reset shell record record-all parity parity-naive \
	java-build java-test parity-java parity-java-codecs \
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

# Java port (java/). CODEC=python|java selects the codec that reads the fixture
# inputs and encodes the candidate datasets; java = legacy-adapter (COG-1240).
CODEC ?= python
JAVA_CASES = $(or $(CASE),$(sort $(notdir $(patsubst %/case.json,%,$(wildcard fixtures/xferfee/*/case.json)))))
JAVA_OUT = work/parity-java$(if $(filter java,$(CODEC)),/java-codec)
MVN = mvn -B -q -Dmaven.repo.local=/estate/work/m2 $(MVN_FLAGS)

java-build:
	docker compose exec -T estate $(MVN) -f java/pom.xml -DskipTests install

java-test:
	docker compose exec -T estate $(MVN) -f java/pom.xml verify

parity-java: java-build
	docker compose exec -T estate sh -c 'rc=0; for c in $(JAVA_CASES); do \
		python3 tools/parity/java_candidate.py --case $$c --codec $(CODEC) || exit 1; \
		python3 tools/parity/compare.py --chain xferfee --case $$c \
		--candidate $(JAVA_OUT)/$$c/candidate \
		--report $(JAVA_OUT)/$$c/report.md || rc=1; \
	done; exit $$rc'

parity-java-codecs: java-build
	docker compose exec -T estate sh -c 'for c in $(JAVA_CASES); do \
		python3 tools/parity/java_candidate.py --case $$c --codec python && \
		python3 tools/parity/java_candidate.py --case $$c --codec java || exit 1; \
	done; python3 tools/parity/codec_identity.py $(addprefix --case ,$(JAVA_CASES))'

deadcode:
	python3 tools/deadcode/gen_smf.py
	python3 tools/deadcode/retire_split.py

chain-graph:
	python3 tools/chaingraph/gen_chain_graph.py

chain-graph-check:
	python3 tools/chaingraph/gen_chain_graph.py --check
