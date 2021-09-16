.PHONY: test dev-db test-db clean-test-db clean-dev-db build-jar build-docker

test: clean-test-db test-db
	@printf "\nRunning unittest...\n"
	lein test :all

dev-db: clean-dev-db
	@printf "\nLaunch postgres database...(default password: password)\n"
	@docker run --name tservice -e POSTGRES_PASSWORD=password -e POSTGRES_USER=postgres -p 5432:5432 -d postgres:10.0
	@sleep 3
	@echo "Create database: tservice_dev"
	@bash build/create-db.sh tservice_dev 5432
	@echo "Migrate database..."
	@bash lein run migrate

test-db:
	@printf "\nLaunch postgres database...(default password: password)\n"
	@docker run --name tservice-test -e POSTGRES_PASSWORD=password -e POSTGRES_USER=postgres -p 54320:5432 -d postgres:10.0
	@sleep 3
	@echo "Create database: datains_test"
	@bash build/create-db.sh datains_test 54320

clean-test-db:
	@printf "Stop "
	@-docker stop tservice-test
	@printf "Clean "
	@-docker rm tservice-test

clean-dev-db:
	@printf "Stop "
	@-docker stop tservice
	@printf "Clean "
	@-docker rm tservice

build-jar:
	@printf "Make tservice.jar package...\n"
	@lein uberjar
	@printf "Your package is prepared into target directory..."

build-docker:
	@echo "Building docker image"
	@bash build/build-docker.sh