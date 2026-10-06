.PHONY: up down build run reset shell record record-all parity parity-naive \
	deadcode chain-graph chain-graph-check parity-console parity-console-check \
	parity-console-down

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

CONSOLE_COMPOSE = docker compose -f docker-compose.yml -f parity-console/compose.yaml

parity-console:
	$(CONSOLE_COMPOSE) up -d --build --wait db java-live parity-console
	$(CONSOLE_COMPOSE) exec -T parity-console sh -c '[ -f loadlib/XFERFEE.so ] || tools/build.sh'
	@echo "Parity console: http://localhost:$${PARITY_CONSOLE_PORT:-8090}"

parity-console-check:
	$(CONSOLE_COMPOSE) exec -T parity-console python3 parity-console/console/headless.py --all
	$(CONSOLE_COMPOSE) exec -T parity-console python3 parity-console/console/headless.py \
		--break-java --stream half_cent

parity-console-down:
	$(CONSOLE_COMPOSE) rm -sf parity-console java-live live-db-init
