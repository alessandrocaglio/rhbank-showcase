.PHONY: start stop restart status logs build smoke help

start:
	@./showcase.sh start

stop:
	@./showcase.sh stop

restart:
	@./showcase.sh restart

status:
	@./showcase.sh status

logs:
	@./showcase.sh logs

build:
	@./showcase.sh build

smoke:
	@./showcase.sh smoke

help:
	@./showcase.sh help
