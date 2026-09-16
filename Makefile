.PHONY: up down build run reset shell record record-all parity parity-naive \
	deadcode chain-graph chain-graph-check \
	mvs-up mvs-install mvs-3270 mvs-kicks mvs-minimal mvs-carddemo mvs-down

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

mvs-up:
	docker compose --profile mvs up -d --build mvs
	docker compose exec -T mvs python3 /opt/kicks/mvs3270.py wait-ipl

mvs-install:
	docker compose exec -T mvs python3 /opt/kicks/mvs3270.py install-kicks

mvs-3270:
	c3270 -model 3279-2 localhost:3270 || docker compose exec mvs c3270 -model 3279-2 127.0.0.1:3270

mvs-kicks:
	docker compose exec -T mvs python3 /opt/kicks/mvs3270.py kicks

mvs-minimal:
	docker compose exec -T mvs python3 /opt/kicks/mvs3270.py minimal

mvs-carddemo:
	docker compose exec -T mvs python3 /opt/kicks/mvs3270.py carddemo

mvs-down:
	docker compose --profile mvs stop mvs
