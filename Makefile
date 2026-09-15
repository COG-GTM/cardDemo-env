.PHONY: up down build run reset shell

up:
	docker compose up -d --build --wait

down:
	docker compose down

build:
	docker compose exec -T estate tools/build.sh

run:
	docker compose exec -T estate python3 tools/runjcl/runjcl.py --chain $(CHAIN) --load-fixtures default --db-reset

reset:
	rm -rf datasets work loadlib
	docker compose down -v

shell:
	docker compose exec estate bash
