.DEFAULT_GOAL := help

THIS_FILE := $(lastword $(MAKEFILE_LIST))
ROOT_DIR := $(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))

network:  ## create the cg Docker network
	@docker network create cg

db-build:  ## build the PostgreSQL image
	@docker build -f src/postgres/Dockerfile -t cg-postgres:1.0 src/postgres

db-run:  ## run the PostgreSQL container
	@docker run \
		-d \
		--network cg \
		-v "$(ROOT_DIR)/data/db:/data" \
		--env-file .secretenv.list \
		--name cg-db \
		cg-postgres:1.0

db-load:  ## load a SQL dump file into the postgres db
	@docker exec cg-db sh -c "psql cgprod cgprod < /data/dump-2020-12-07.sql"

db-stop-rm:  ## stop and remove the postgres db container
	@docker stop cg-db && docker rm cg-db

psql:  ## open psql to access the cghousing db CLI
	@docker exec -it cg-db psql cgprod cgprod

dj-build:  ## build the Django CGHousing image
	@docker build -f src/cghousing/Dockerfile -t cg-django:1.0 src/cghousing

dj-run:  ## run the Django CGHousing container
	@docker run \
		-d \
		--network cg \
		--name cg-django \
		-v "$(ROOT_DIR)/data/static:/static" \
		-v "$(ROOT_DIR)/data/uploads:/app/uploads" \
    -v "$(ROOT_DIR)/src/cghousing/cghousing:/app/cghousing" \
		--env-file ./.secretenv.list \
		--env CG_ALLOWED_HOSTS=* \
		--env CG_TIME_ZONE=America/Vancouver \
		--env CG_STATIC_ROOT=/static \
		--env CG_DB_HOST=cg-db \
		--env CG_DB_PORT=5432 \
    --env CG_UPLOADS_PATH=uploads \
		cg-django:1.0 cghousing/run.sh

dj-stop-rm:  ## stop and remove the Django CGHousing container
	@docker stop cg-django && docker rm cg-django

dj-log:  ## show logs from the CG Django container
	@docker logs -f cg-django

dj-shell:  ## open a Python interactive Django prompt in the running CG Django container
	@docker exec -it cg-django python cghousing/manage.py shell

dj-sh:  ## open a Bourne shell in a newly run Django CGHousing container (DEBUG ONLY)
	@docker run \
    -it \
		--network cg \
		--name cg-django \
		-v "$(ROOT_DIR)/data/static:/static" \
		-v "$(ROOT_DIR)/data/uploads:/app/uploads" \
		--env-file ./.secretenv.list \
		--env CG_ALLOWED_HOSTS=* \
		--env CG_TIME_ZONE=America/Vancouver \
		--env CG_STATIC_ROOT=/static \
		--env CG_DB_HOST=cg-db \
		--env CG_DB_PORT=5432 \
    --env CG_UPLOADS_PATH=uploads \
		cg-django:1.0 sh

server-build:  ## build the Nginx image
	@docker build -f src/nginx/Dockerfile -t cg-nginx:1.0 src/nginx

server-run:  ## run the Nginx CGHousing container
	@docker run \
		-d \
		--network cg \
		--name cg-server \
		-v "$(ROOT_DIR)/data/static:/static:ro" \
		-v "$(ROOT_DIR)/src/nginx/html:/var/www/html" \
		-v "$(ROOT_DIR)/src/nginx/private/certs:/etc/letsencrypt" \
		-v "$(ROOT_DIR)/src/nginx/etc/nginx.conf:/etc/nginx/nginx.conf" \
		-v "$(ROOT_DIR)/src/nginx/etc/cg:/etc/nginx/sites-enabled/cg" \
		-p 6080:80 \
		cg-nginx:1.0

server-sh:  ## run sh shell in the Nginx CGHousing container
	@docker run \
		-it \
		--network cg \
		--name cg-server \
		-v "$(ROOT_DIR)/data/static:/static:ro" \
		-v "$(ROOT_DIR)/src/nginx/html:/var/www/html" \
		-v "$(ROOT_DIR)/src/nginx/private/certs:/etc/letsencrypt" \
		-v "$(ROOT_DIR)/src/nginx/etc/nginx.conf:/etc/nginx/nginx.conf" \
		-v "$(ROOT_DIR)/src/nginx/etc/cg:/etc/nginx/sites-enabled/cg" \
		-p 6080:80 \
		cg-nginx:1.0 sh

server-stop-rm:  ## stop and remove the Nginx server container
	@docker stop cg-server && docker rm cg-server

help:  ## Print this help message.
	@grep -E '^[0-9a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'
